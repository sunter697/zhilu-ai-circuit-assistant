@echo off
setlocal
cd /d "%~dp0"
set "PYTHONPATH=%CD%\src"
set "VISION_PYTHON=python"
if exist "%CD%\build\release-env\Scripts\python.exe" set "VISION_PYTHON=%CD%\build\release-env\Scripts\python.exe"
if not exist "%CD%\build\release-env\Scripts\python.exe" if exist "%CD%\build\onnx-test-env\Scripts\python.exe" set "VISION_PYTHON=%CD%\build\onnx-test-env\Scripts\python.exe"
if not exist "%CD%\models\best.onnx" (
  echo ERROR: Missing frozen model: %CD%\models\best.onnx
  echo Copy the submitted best.onnx file before starting the service.
  pause
  exit /b 1
)
echo Starting Logisim Circuit Vision on http://127.0.0.1:8765
"%VISION_PYTHON%" -m logisim_vision.app --host 127.0.0.1 --port 8765
if errorlevel 1 pause
