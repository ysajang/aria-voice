@echo off
echo ============================================
echo  ARIA Voice - ONNX Model Downloader
echo ============================================
echo.

set ASSETS_DIR=app\src\main\assets

if not exist %ASSETS_DIR% mkdir %ASSETS_DIR%

echo [1/3] Downloading melspectrogram.onnx...
curl -L -o %ASSETS_DIR%\melspectrogram.onnx https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx

echo [2/3] Downloading embedding_model.onnx...
curl -L -o %ASSETS_DIR%\embedding_model.onnx https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx

echo [3/3] Downloading hey_jarvis_v0.1.onnx (temporary wake word)...
curl -L -o %ASSETS_DIR%\hey_jarvis_v0.1.onnx https://huggingface.co/davidscripka/openwakeword/resolve/main/hey_jarvis_v0.1.onnx

echo.
echo ============================================
echo  Download complete!
echo  Models saved to: %ASSETS_DIR%
echo ============================================
dir %ASSETS_DIR%\*.onnx
pause
