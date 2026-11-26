"""Compatibilité glibc 2.44. Rendre la pile non exécutable, un seul octet ELF change."""
import hashlib
import json
import struct
import sysconfig
from pathlib import Path


def apply():
    lib = Path(sysconfig.get_path('purelib')) / 'torch/lib/libtorch_cpu.so'
    with lib.open('r+b') as f:
        header = f.read(64)
        assert header[:6] == b'\x7fELF\x02\x01'
        begin, = struct.unpack_from('<Q', header, 32)
        size, count = struct.unpack_from('<HH', header, 54)
        offsets = []
        for i in range(count):
            where = begin + i * size
            f.seek(where)
            kind, flags = struct.unpack('<II', f.read(8))
            if kind == 0x6474e551:
                assert flags == 7
                offsets.append(where + 4)
        assert len(offsets) == 1
        f.seek(0)
        original = hashlib.sha256(f.read()).hexdigest()
        f.seek(offsets[0])
        f.write(b'\x06')
    data = dict(fichier=lib.name, segment='PT_GNU_STACK', avant='RWE', apres='RW',
                octet=offsets[0], sha256_original=original, sha256_installe=hashlib.sha256(lib.read_bytes()).hexdigest())
    Path('r27/pile-elf.json').write_text(json.dumps(data, indent=2)+'\n')


if __name__ == '__main__':
    apply()
