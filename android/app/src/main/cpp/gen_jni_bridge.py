# Generate JNI symbol bridge: com.photographercamera.photon.* Kotlin tree
# forwards to existing com.photographercamera.core.photon.* native impls.
import re, os, sys

CPP = os.path.dirname(os.path.abspath(__file__))
FILES = [f for f in os.listdir(CPP) if f.endswith('.cpp')]
SUBDIR = os.path.join(CPP, 'mgc_denoise_static')
if os.path.isdir(SUBDIR):
    FILES += [os.path.join('mgc_denoise_static', f) for f in os.listdir(SUBDIR) if f.endswith('.cpp')]

# subpath_class mapping: old core_photon_<sub>_<Class> -> photon_<newsub>_<Class>
MAPPING = {
    'color_LutProcessor': 'lut_LutProcessor',
    'gallery_Jpeg444ExportEncoder': 'gallery_Jpeg444ExportEncoder',
    'ml_DnCNNDenoiseEstimator': 'ml_DnCNNDenoiseEstimator',
    'raw_DcpNativeBridge': 'raw_DcpNativeBridge',
    'raw_DngHdrNetProfileGainTableNative': 'raw_DngHdrNetProfileGainTableNative',
    'raw_DngProfileGainTableValidation': 'raw_DngProfileGainTableValidation',
    'raw_DngRawData': 'raw_DngRawData',
    'raw_MgcFullResolutionDenoise': 'raw_MgcFullResolutionDenoise',
    'raw_RawDemosaicProcessor': 'raw_RawDemosaicProcessor',
    'raw_RawLegacyAutoExposureNativeBridge': 'raw_RawLegacyAutoExposureNativeBridge',
    'stabilization_MgcEisNativeBridge': 'stabilization_MgcEisNativeBridge',
    'stack_DirectBufferAllocator': 'utils_DirectBufferAllocator',
    'stack_DirectBufferPixelPacker': 'utils_DirectBufferPixelPacker',
    'stack_GlesPixelBufferTransfer': 'processor_GlesPixelBufferTransfer',
    'stack_MgcSabreResolver': 'processor_MgcSabreResolver',
    'stack_MgcSpatialRgbMerger': 'processor_MgcSpatialRgbMerger',
    'stack_MgcSpatialStrengthMapGenerator': 'processor_MgcSpatialStrengthMapGenerator',
    'stack_MgcSpatialStrengthMapScaler': 'processor_MgcSpatialStrengthMapScaler',
    'stack_SuperResolutionDngWriter': 'utils_SuperResolutionDngWriter',
    'stack_YuvProcessor': 'utils_YuvProcessor',
}

TYPE_WORDS = {'JNIEnv', 'jobject', 'jclass', 'jstring', 'jint', 'jlong', 'jfloat', 'jdouble',
              'jboolean', 'jbyte', 'jshort', 'jchar', 'jsize', 'jthrowable', 'AHardwareBuffer',
              'ANativeWindow', 'void', 'jweak'}

pat = re.compile(
    r'JNIEXPORT\s+([\w\s\*]+?)\s+JNICALL\s*\n?\s*'
    r'(Java_com_photographercamera_core_photon_\w+)\s*\(([^)]*)\)\s*\{', re.DOTALL)

found = {}
for f in FILES:
    path = os.path.join(CPP, f)
    text = open(path, encoding='utf-8', errors='replace').read()
    for m in pat.finditer(text):
        ret = ' '.join(m.group(1).split())
        name = m.group(2)
        params_raw = m.group(3)
        if name in found:
            continue
        # parse params: split top-level commas, strip comments, attach names
        parts = []
        depth = 0
        cur = ''
        for ch in params_raw:
            if ch in '(<[':
                depth += 1
            elif ch in ')>]':
                depth -= 1
            if ch == ',' and depth == 0:
                parts.append(cur); cur = ''
            else:
                cur += ch
        if cur.strip():
            parts.append(cur)
        plist = []  # (decl, argname)
        ok = True
        for i, p in enumerate(parts):
            clean = re.sub(r'/\*.*?\*/', '', p, flags=re.DOTALL).strip()
            toks = clean.replace('*', ' * ').split()
            if not toks:
                ok = False; break
            # find name: last token that is not a type keyword and not starting with *
            name_tok = None
            for t in reversed(toks):
                if t.startswith('*'):
                    continue
                if t in TYPE_WORDS or re.match(r'^j\w+$', t) or t in ('const', 'unsigned', 'struct', 'enum'):
                    continue
                name_tok = t
                break
            if name_tok is None:
                name_tok = 'arg%d' % i
                # ensure declaration has a name: append
                if toks and toks[-1].startswith('*'):
                    decl = clean + name_tok
                elif toks and (toks[-1] in TYPE_WORDS or re.match(r'^j\w+$', toks[-1])):
                    decl = clean + ' ' + name_tok
                else:
                    decl = clean + ' ' + name_tok
            else:
                decl = clean
            plist.append((decl, name_tok))
        if not ok or not parts:
            print('SKIP (bad params):', name, file=sys.stderr)
            continue
        found[name] = (ret, plist)

lines = []
lines.append('// AUTO-GENERATED JNI symbol bridge (tools/gen_jni_bridge.py).')
lines.append('// Forwards Java_com_photographercamera_photon_* lookups to the')
lines.append('// com.photographercamera.core.photon.* implementations compiled in this lib.')
lines.append('#include <jni.h>')
lines.append('')
lines.append('extern "C" {')
lines.append('')
# forward declarations of originals
for name in sorted(found):
    ret, plist = found[name]
    decl_params = ', '.join(d for d, _ in plist)
    lines.append('%s %s(%s);' % (ret, name, decl_params))
lines.append('')
# wrappers
n = 0
for name in sorted(found):
    ret, plist = found[name]
    m = re.match(r'Java_com_photographercamera_core_photon_(.+)', name)
    old_tail = m.group(1)
    mapped = None
    for old, new in MAPPING.items():
        if old_tail == old or old_tail.startswith(old + '_'):
            mapped = 'Java_com_photographercamera_photon_' + old_tail.replace(old, new, 1)
            break
    if mapped is None:
        print('NO MAPPING for', name, file=sys.stderr)
        continue
    decl_params = ', '.join(d for d, _ in plist)
    args = ', '.join(a for _, a in plist)
    lines.append('%s %s(%s) {' % (ret, mapped, decl_params))
    if ret.strip() != 'void':
        lines.append('  return %s(%s);' % (name, args))
    else:
        lines.append('  %s(%s);' % (name, args))
    lines.append('}')
    lines.append('')
    n += 1
lines.append('} // extern "C"')
lines.append('')

out = os.path.join(CPP, 'photon_jni_bridge.cpp')
open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('wrappers=%d, total found=%d -> %s' % (n, len(found), out))
