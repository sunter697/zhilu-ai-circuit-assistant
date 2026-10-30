"""Repository-local entry point for the deterministic dataset generator."""

from pathlib import Path
import sys


SERVICE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVICE_ROOT / "src"))

from logisim_vision.dataset import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())

