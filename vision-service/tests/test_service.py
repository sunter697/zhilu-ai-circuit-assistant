from __future__ import annotations

import http.client
import json
from pathlib import Path
import sys
import threading
import unittest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.app import (  # noqa: E402
    RecognitionApplication,
    SCHEMA_VERSION,
    create_server,
)


class _FakeRecognizer:
    def recognize(self, image, source_sha256: str) -> dict[str, object]:
        return {
            "schemaVersion": SCHEMA_VERSION,
            "source": {
                "sha256": source_sha256,
                "width": image.width,
                "height": image.height,
            },
            "profile": {
                "theme": "light",
                "gateShape": "ansi",
                "gridPixels": 10.0,
                "scale": 1.0,
                "offsetX": 0.0,
                "offsetY": 0.0,
            },
            "components": [
                {
                    "id": "input_001",
                    "kind": "INPUT_PIN",
                    "bodyBox": [10, 10, 30, 30],
                    "anchor": [30, 20],
                    "facing": "EAST",
                    "attributes": {"bitWidth": 1},
                    "ports": [
                        {"id": "out", "direction": "OUTPUT", "point": [30, 20]}
                    ],
                    "confidence": 1.0,
                },
                {
                    "id": "output_001",
                    "kind": "OUTPUT_PIN",
                    "bodyBox": [70, 10, 90, 30],
                    "anchor": [70, 20],
                    "facing": "EAST",
                    "attributes": {"bitWidth": 1},
                    "ports": [
                        {"id": "in", "direction": "INPUT", "point": [70, 20]}
                    ],
                    "confidence": 1.0,
                },
            ],
            "nets": [
                {
                    "id": "net_001",
                    "endpoints": [
                        {"componentId": "input_001", "portId": "out"},
                        {"componentId": "output_001", "portId": "in"},
                    ],
                    "segments": [[30, 20, 70, 20]],
                    "junctions": [],
                    "confidence": 1.0,
                }
            ],
            "unresolved": [],
            "semanticHash": "test",
        }


class VisionServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = create_server(
            "127.0.0.1", 0, RecognitionApplication(_FakeRecognizer())
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=5
        )

    def tearDown(self) -> None:
        self.connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_health_and_multipart_recognition(self) -> None:
        self.connection.request("GET", "/health")
        response = self.connection.getresponse()
        health = json.loads(response.read())
        self.assertEqual(response.status, 200)
        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["protocolVersion"], SCHEMA_VERSION)
        self.assertEqual(health["serviceVersion"], "full-v09/0.1")

        image = (
            Path(__file__).parent
            / "fixtures"
            / "real-logisim"
            / "T03_NOT.png"
        ).read_bytes()
        boundary = "logisim-test-boundary"
        body = (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="image"; filename="circuit.png"\r\n'
            "Content-Type: image/png\r\n\r\n"
        ).encode("ascii") + image + f"\r\n--{boundary}--\r\n".encode("ascii")
        self.connection.request(
            "POST",
            "/recognize",
            body=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        response = self.connection.getresponse()
        result = json.loads(response.read())
        self.assertEqual(response.status, 200)
        self.assertEqual(result["schemaVersion"], SCHEMA_VERSION)
        self.assertGreater(result["source"]["width"], 0)
        self.assertEqual(len(result["source"]["sha256"]), 64)

    def test_empty_recognition_is_rejected_with_structured_error(self) -> None:
        original = self.server.application.recognizer

        class EmptyRecognizer:
            def recognize(self, image, source_sha256: str) -> dict[str, object]:
                result = original.recognize(image, source_sha256)
                result["components"] = []
                result["nets"] = []
                return result

        self.server.application.recognizer = EmptyRecognizer()
        image = (
            Path(__file__).parent
            / "fixtures"
            / "real-logisim"
            / "T03_NOT.png"
        ).read_bytes()
        self.connection.request(
            "POST",
            "/recognize",
            body=image,
            headers={"Content-Type": "image/png"},
        )
        response = self.connection.getresponse()
        result = json.loads(response.read())
        self.assertEqual(response.status, 422)
        self.assertEqual(result["error"]["code"], "EMPTY_CIRCUIT")

    def test_non_png_is_rejected_with_structured_error(self) -> None:
        self.connection.request(
            "POST",
            "/recognize",
            body=b"not a png",
            headers={"Content-Type": "application/octet-stream"},
        )
        response = self.connection.getresponse()
        result = json.loads(response.read())
        self.assertEqual(response.status, 422)
        self.assertEqual(result["error"]["code"], "UNSUPPORTED_IMAGE")

    def test_non_loopback_bind_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            create_server("0.0.0.0", 0, RecognitionApplication(_FakeRecognizer()))

    def test_second_service_cannot_reuse_the_same_port(self) -> None:
        with self.assertRaises(OSError):
            create_server(
                "127.0.0.1",
                self.server.server_port,
                RecognitionApplication(_FakeRecognizer()),
            )

    def test_service_can_restart_immediately_after_a_request(self) -> None:
        server = create_server(
            "127.0.0.1", 0, RecognitionApplication(_FakeRecognizer())
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_port
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        connection.request("GET", "/health")
        response = connection.getresponse()
        response.read()
        self.assertEqual(response.status, 200)
        connection.close()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

        restarted = create_server(
            "127.0.0.1", port, RecognitionApplication(_FakeRecognizer())
        )
        restarted.server_close()


if __name__ == "__main__":
    unittest.main()
