"""Minimal LuaJIT 2.0 (bytecode v1) reader/disassembler for Gauntlet's scripts."""
import sys
OPS = ("ISLT ISGE ISLE ISGT ISEQV ISNEV ISEQS ISNES ISEQN ISNEN ISEQP ISNEP ISTC ISFC IST ISF MOV NOT UNM LEN "
       "ADDVN SUBVN MULVN DIVVN MODVN ADDNV SUBNV MULNV DIVNV MODNV ADDVV SUBVV MULVV DIVVV MODVV POW CAT KSTR "
       "KCDATA KSHORT KNUM KPRI KNIL UGET USETV USETS USETN USETP UCLO FNEW TNEW TDUP GGET GSET TGETV TGETS TGETB "
       "TSETV TSETS TSETB TSETM CALLM CALL CALLMT CALLT ITERC ITERN VARG ISNEXT RETM RET RET0 RET1 FORI JFORI FORL "
       "IFORL JFORL ITERL IITERL JITERL LOOP ILOOP JLOOP JMP FUNCF IFUNCF JFUNCF FUNCV IFUNCV JFUNCV FUNCC FUNCCW").split()
def uleb(d, o):
    v = s = 0
    while True:
        b = d[o]; o += 1; v |= (b & 0x7f) << s; s += 7
        if b < 0x80: return v, o
def ktabk(d, o):
    t, o = uleb(d, o)
    if t >= 5: return d[o:o + t - 5].decode('latin1'), o + t - 5
    if t == 3: return uleb(d, o)
    if t == 4: a, o = uleb(d, o); b, o = uleb(d, o); return ('num', a, b), o
    return [None, False, True][t], o
def parse(d):
    assert d[:3] == b'\x1bLJ'; o = 4
    flags, o = uleb(d, o)
    strip = flags & 2
    if not strip: n, o = uleb(d, o); name = d[o:o + n]; o += n
    protos = []
    while o < len(d):
        size, o2 = uleb(d, o)
        if size == 0: break
        p = {'start': o, 'body': o2, 'end': o2 + size}
        q = o2
        p['flags'], p['np'], p['fs'], p['nuv'] = d[q], d[q + 1], d[q + 2], d[q + 3]; q += 4
        nkgc, q = uleb(d, q); nkn, q = uleb(d, q); nbc, q = uleb(d, q)
        if not strip:
            dbg, q = uleb(d, q)
            if dbg: fl, q = uleb(d, q); nl, q = uleb(d, q); p['line'] = fl
        p['bc'] = q; p['nbc'] = nbc; q += nbc * 4; q += p['nuv'] * 2
        kgc = []
        for i in range(nkgc):
            t, q = uleb(d, q)
            if t >= 5: kgc.append(d[q:q + t - 5].decode('latin1')); q += t - 5
            elif t == 1:
                na, q = uleb(d, q); nh, q = uleb(d, q); arr = []; h = {}
                for _ in range(na): v, q = ktabk(d, q); arr.append(v)
                for _ in range(nh): k, q = ktabk(d, q); v, q = ktabk(d, q); h[k] = v
                kgc.append(('TAB', arr, h))
            elif t == 0: kgc.append(('CHILD',))
            else: raise Exception('kgc type %d' % t)
        p['kgc'] = kgc; protos.append(p); o = p['end']
    return protos
def ins(d, p, i):
    a = p['bc'] + 4 * i
    op, A, C, B = d[a], d[a + 1], d[a + 2], d[a + 3]; D = C | (B << 8)
    return OPS[op] if op < len(OPS) else op, A, B, C, D
def dis(d, p):
    n = len(p['kgc'])
    for i in range(p['nbc']):
        op, A, B, C, D = ins(d, p, i)
        extra = ''
        if op in ('KSTR', 'TDUP', 'GGET', 'GSET', 'TGETS', 'TSETS') :
            k = p['kgc'][n - 1 - (C if op in ('TGETS', 'TSETS') else D)]
            extra = repr(k)[:140]
        print('%4d %-7s A=%d B=%d C=%d D=%d %s' % (i, op, A, B, C, D, extra))
if __name__ == '__main__':
    d = open(sys.argv[1], 'rb').read(); ps = parse(d)
    for j, p in enumerate(ps):
        print('proto', j, 'line', p.get('line'), 'nbc', p['nbc'], 'kgc', len(p['kgc']))
