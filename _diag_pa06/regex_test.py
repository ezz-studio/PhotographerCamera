import re

src = open("tools/static_cross_check.py", encoding="utf-8").read()
i = src.find("lookup_re")
print("FILE CONTENT:", repr(src[i:i+130]))

lookup_re = re.compile(r'[gG]etUniformLocation\(\s*[^,]+?,\s*"(u_[A-Za-z0-9_]+)"')
t = 'uMVPMatrixLocation = GLES30.glGetUniformLocation(program, "uMVPMatrix"),'
print("match on sample:", lookup_re.findall(t))

# also test against the real file
kt = open("android/app/src/main/java/com/photographercamera/photon/lut/ColorPassProgram.kt", encoding="utf-8").read()
print("matches in ColorPassProgram.kt:", len(lookup_re.findall(kt)))
declare_re = re.compile(r"uniform\s+\w+\s+(u_[A-Za-z0-9_]+)")
fg = open("android/app/src/main/java/com/photographercamera/photon/lut/FilmGrainShaders.kt", encoding="utf-8").read()
print("declares in FilmGrainShaders.kt:", declare_re.findall(fg)[:5])
