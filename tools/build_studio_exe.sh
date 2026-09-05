#!/usr/bin/env bash
# Package the Studio workbench into a single Windows executable.
#
#   dist/PhotographerStudio.exe  — double-click to run; opens the browser UI.
#
# The bundle embeds the code + web assets + JSON schema. On first run the app
# creates a workspace next to the .exe (profiles/, studio_session/) so user data
# is never written into the temp extraction directory.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PY="C:/Users/EDY/.workbuddy/binaries/python/versions/3.13.12/python.exe"

echo "==> cleaning previous build artifacts"
rm -rf build dist PhotographerStudio.spec

echo "==> running PyInstaller"
"$PY" -m PyInstaller \
  --noconfirm --clean --onefile --console \
  --name PhotographerStudio \
  --paths . --paths tools \
  --add-data "desktop/web;desktop/web" \
  --add-data "profiles/schema;profiles/schema" \
  --hidden-import style_analyzer \
  --hidden-import ai_profile_generator \
  --hidden-import profile_optimizer \
  --hidden-import profile_validator \
  --hidden-import build_profile \
  --hidden-import profile_renderer \
  --hidden-import profile_schema \
  --hidden-import dataset_loader \
  --hidden-import glsl_reference \
  --hidden-import PIL.Image \
  --exclude-module tkinter \
  --exclude-module matplotlib \
  --exclude-module pytest \
  desktop/server.py

if [ -f dist/PhotographerStudio.exe ]; then
  echo "==> OK: $(ls -lh dist/PhotographerStudio.exe | awk '{print $5, $9}')"
else
  echo "==> FAILED: dist/PhotographerStudio.exe not produced"
  exit 1
fi
