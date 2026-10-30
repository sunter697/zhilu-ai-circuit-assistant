"""Loopback-only HTTP service for Logisim circuit image recognition."""

from __future__ import annotations

import argparse
import ctypes
from email.parser import BytesParser
from email.policy import default
from hashlib import sha256
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
import os
from pathlib import Path
from typing import Any

from PIL import Image, UnidentifiedImageError

from .onnx_detector import (
    FULL_CLASS_NAMES,
    HybridComponentDetector,
    OnnxComponentDetector,
)
from .template_detector import DETECTOR_VERSION, FixedScaleTemplateDetector
from .topology import FixedScaleTopologyRecognizer, SCHEMA_VERSION


SERVICE_VERSION = "full-v09/0.1"
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_IMAGE_PIXELS = 4096 * 4096


class RecognitionApplication:
    def __init__(self, recognizer: Any | None = None) -> None:
        self.detector_mode = "template-fallback"
        self.model_version = DETECTOR_VERSION
        self.model_sha256: str | None = None
        if recognizer is not None:
            self.recognizer = recognizer
            self.detector_mode = "injected-test"
            self.model_version = "test"
            return

        detector: Any = FixedScaleTemplateDetector()
        model_path = Path(
            os.environ.get(
                "LOGISIM_VISION_MODEL",
                str(Path(__file__).resolve().parents[2] / "models" / "best.onnx"),
            )
        )
        if model_path.is_file():
            try:
                onnx_detector = OnnxComponentDetector(model_path)
                if onnx_detector.class_names == FULL_CLASS_NAMES:
                    legacy_model_path = Path(
                        os.environ.get(
                            "LOGISIM_VISION_LEGACY_MODEL",
                            str(
                                Path(__file__).resolve().parents[2]
                                / "models"
                                / "legacy-week1.onnx"
                            ),
                        )
                    )
                    compatibility_detector = None
                    if legacy_model_path.is_file():
                        try:
                            candidate = OnnxComponentDetector(legacy_model_path)
                            if candidate.class_names != FULL_CLASS_NAMES:
                                compatibility_detector = candidate
                        except RuntimeError:
                            # A damaged optional compatibility model must not
                            # disable the primary nine-class model.
                            pass
                    detector = HybridComponentDetector(
                        onnx_detector,
                        compatibility_detector=compatibility_detector,
                    )
                    if compatibility_detector is not None:
                        self.detector_mode = (
                            "onnx-9-class-with-profile-routed-legacy-compensation"
                        )
                    else:
                        self.detector_mode = (
                            "onnx-9-class-with-profile-calibrated-compensation"
                        )
                else:
                    detector = HybridComponentDetector(onnx_detector)
                    self.detector_mode = "onnx-with-profile-calibrated-compensation"
                self.model_sha256 = sha256(model_path.read_bytes()).hexdigest()
                self.model_version = f"best.onnx:{self.model_sha256[:12]}"
                if (
                    onnx_detector.class_names == FULL_CLASS_NAMES
                    and compatibility_detector is not None
                ):
                    legacy_digest = sha256(legacy_model_path.read_bytes()).hexdigest()[:12]
                    self.model_version += f"+legacy-week1.onnx:{legacy_digest}"
            except RuntimeError:
                # A clear detectorMode in /health exposes the fallback.  The
                # fixed profile stays usable even when onnxruntime is absent.
                pass
        self.recognizer = FixedScaleTopologyRecognizer(component_detector=detector)

    def health(self) -> dict[str, object]:
        return {
            "status": "ok",
            "protocolVersion": SCHEMA_VERSION,
            "serviceVersion": SERVICE_VERSION,
            "modelVersion": self.model_version,
            "modelSha256": self.model_sha256,
            "detectorMode": self.detector_mode,
        }

    def recognize(self, image_bytes: bytes) -> dict[str, object]:
        if not image_bytes:
            raise ServiceInputError("EMPTY_IMAGE", "The uploaded image is empty")
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise ServiceInputError("IMAGE_TOO_LARGE", "The image exceeds 10 MiB")
        try:
            with Image.open(BytesIO(image_bytes)) as opened:
                if opened.format != "PNG":
                    raise ServiceInputError(
                        "UNSUPPORTED_IMAGE", "Only PNG images are supported"
                    )
                if opened.width * opened.height > MAX_IMAGE_PIXELS:
                    raise ServiceInputError(
                        "IMAGE_TOO_LARGE", "The image dimensions are too large"
                    )
                image = opened.convert("RGB")
                image.load()
        except UnidentifiedImageError as exception:
            raise ServiceInputError(
                "UNSUPPORTED_IMAGE", "The uploaded file is not a readable image"
            ) from exception
        try:
            result = self.recognizer.recognize(
                image, sha256(image_bytes).hexdigest()
            )
        except ValueError as exception:
            raise ServiceInputError("UNSUPPORTED_PROFILE", str(exception)) from exception
        if not result.get("components"):
            raise ServiceInputError(
                "EMPTY_CIRCUIT", "No supported circuit components were found"
            )
        return result


class ServiceInputError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class _WindowsInstanceMutex:
    """Process-owned lock that prevents two Windows services using one port."""

    ERROR_ALREADY_EXISTS = 183

    def __init__(self, port: int) -> None:
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32.CreateMutexW.argtypes = [
            ctypes.c_void_p,
            ctypes.c_int,
            ctypes.c_wchar_p,
        ]
        self._kernel32.CreateMutexW.restype = ctypes.c_void_p
        self._kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
        self._kernel32.CloseHandle.restype = ctypes.c_int
        name = f"Local\\LogisimCircuitVision-{port}"
        self._handle = self._kernel32.CreateMutexW(None, 0, name)
        error = ctypes.get_last_error()
        if not self._handle:
            raise ctypes.WinError(error)
        if error == self.ERROR_ALREADY_EXISTS:
            self.close()
            raise OSError(10048, f"A vision service already owns port {port}")

    def close(self) -> None:
        if self._handle:
            self._kernel32.CloseHandle(self._handle)
            self._handle = None


class RecognitionHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        application: RecognitionApplication,
    ) -> None:
        self._instance_mutex: _WindowsInstanceMutex | None = None
        super().__init__(server_address, RecognitionRequestHandler)
        try:
            if os.name == "nt":
                self._instance_mutex = _WindowsInstanceMutex(self.server_port)
        except Exception:
            super().server_close()
            raise
        self.application = application

    def server_close(self) -> None:
        super().server_close()
        if self._instance_mutex is not None:
            self._instance_mutex.close()
            self._instance_mutex = None


class RecognitionRequestHandler(BaseHTTPRequestHandler):
    server: RecognitionHttpServer

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path != "/health":
            self._error(HTTPStatus.NOT_FOUND, "NOT_FOUND", "Unknown endpoint")
            return
        self._json(HTTPStatus.OK, self.server.application.health())

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path != "/recognize":
            self._error(HTTPStatus.NOT_FOUND, "NOT_FOUND", "Unknown endpoint")
            return
        try:
            image_bytes = self._read_image_part()
            result = self.server.application.recognize(image_bytes)
            self._json(HTTPStatus.OK, result)
        except ServiceInputError as exception:
            self._error(HTTPStatus.UNPROCESSABLE_ENTITY, exception.code, str(exception))
        except Exception:
            self._error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Circuit recognition failed",
            )

    def _read_image_part(self) -> bytes:
        length_text = self.headers.get("Content-Length")
        if length_text is None:
            raise ServiceInputError("INVALID_REQUEST", "Content-Length is required")
        try:
            length = int(length_text)
        except ValueError as exception:
            raise ServiceInputError(
                "INVALID_REQUEST", "Content-Length is invalid"
            ) from exception
        if length <= 0 or length > MAX_IMAGE_BYTES + 64 * 1024:
            raise ServiceInputError("IMAGE_TOO_LARGE", "The request body is too large")
        content_type = self.headers.get("Content-Type", "")
        body = self.rfile.read(length)
        if content_type == "image/png" or content_type == "application/octet-stream":
            return body
        if not content_type.lower().startswith("multipart/form-data"):
            raise ServiceInputError(
                "INVALID_REQUEST", "multipart/form-data is required"
            )
        message = BytesParser(policy=default).parsebytes(
            f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode(
                "ascii"
            )
            + body
        )
        if not message.is_multipart():
            raise ServiceInputError("INVALID_REQUEST", "Invalid multipart body")
        for part in message.iter_parts():
            if part.get_param("name", header="content-disposition") == "image":
                return part.get_payload(decode=True) or b""
        raise ServiceInputError("INVALID_REQUEST", "Multipart field 'image' is required")

    def _error(self, status: HTTPStatus, code: str, message: str) -> None:
        self._json(status, {"error": {"code": code, "message": message}})

    def _json(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        encoded = json.dumps(
            payload, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: object) -> None:
        print(f"vision-service {self.address_string()} {format % args}")


def create_server(
    host: str = "127.0.0.1",
    port: int = 8765,
    application: RecognitionApplication | None = None,
) -> RecognitionHttpServer:
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("The one-week service may only bind to a loopback address")
    return RecognitionHttpServer((host, port), application or RecognitionApplication())


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the Logisim vision service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    arguments = parser.parse_args()
    try:
        server = create_server(arguments.host, arguments.port)
    except OSError as exception:
        print(
            f"Cannot start Logisim vision service on "
            f"http://{arguments.host}:{arguments.port}: {exception}",
            flush=True,
        )
        return 2
    health = server.application.health()
    print(
        f"Logisim vision service listening on http://{arguments.host}:{arguments.port} "
        f"detector={health['detectorMode']}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
