# -*- mode: python ; coding: utf-8 -*-


from PyInstaller.utils.hooks import collect_submodules

# scipy is imported lazily *inside* functions (style_analyzer -> ndimage.laplace,
# profile_optimizer -> optimize.minimize), which the static graph can miss —
# without these the built exe dies at "generate" time with ImportError.
_hidden = [
    'style_analyzer', 'ai_profile_generator', 'profile_optimizer', 'profile_validator',
    'build_profile', 'profile_renderer', 'profile_schema', 'dataset_loader',
    'loss_function', 'glsl_reference', 'PIL.Image',
]
_hidden += collect_submodules('scipy.ndimage')
_hidden += collect_submodules('scipy.optimize')

a = Analysis(
    ['desktop/server.py'],
    pathex=['.', 'tools'],
    binaries=[],
    datas=[('desktop/web', 'desktop/web'), ('profiles/schema', 'profiles/schema')],
    hiddenimports=_hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['tkinter', 'matplotlib', 'pytest', 'torch', 'IPython', 'notebook'],
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
