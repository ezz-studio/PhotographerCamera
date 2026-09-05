# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['desktop/server.py'],
    pathex=['.', 'tools'],
    binaries=[],
    datas=[('desktop/web', 'desktop/web'), ('profiles/schema', 'profiles/schema')],
    hiddenimports=['style_analyzer', 'ai_profile_generator', 'profile_optimizer', 'profile_validator', 'build_profile', 'profile_renderer', 'profile_schema', 'dataset_loader', 'glsl_reference', 'PIL.Image'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'pytest'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='PhotographerStudio',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
