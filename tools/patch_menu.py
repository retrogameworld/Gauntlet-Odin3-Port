"""Removes Gauntlet's online entries (Start Game, Quick Join, Leaderboards) from the main menu.

Patches lua/menu/screen_main_menu.lua (LuaJIT 2.0 bytecode) inside bundle 4f2d313d15f129af of the GOG game:
  * proto 29 (builds the menu): the table.insert calls for play_online, quick_join, the divider below them and
    leaderboards become no-ops; the Local Game button's template gets selected=true (it was Start Game's)
  * proto 30 (back to the top menu): selects play_offline instead of play_online
  * proto 0 (init): the default selection is play_offline
Writes bundles/out/4f2d313d15f129af. Verifies the original bytes before changing anything.
"""
import struct, zlib, os, sys
import bdl, ljdis

GAME = os.environ.get("GAUNTLET_GAME", "C:/Games/Gauntlet")
BUILD = os.environ.get("GAUNTLET_BUILD", os.path.join(os.path.dirname(os.path.abspath(__file__)), "build"))
SRC = os.path.join(GAME, "contents", "4f2d313d15f129af")
OUT = os.path.join(BUILD, "xbox_buttons", "4f2d313d15f129af")
OUT_ODIN = os.path.join(BUILD, "odin_buttons", "4f2d313d15f129af")

def uleb_enc(v):
    o = bytearray()
    while True:
        b = v & 0x7f; v >>= 7
        if v: o.append(b | 0x80)
        else: o.append(b); return bytes(o)

def kgc_spans(d, p):
    """byte spans of each kgc entry of a proto (file order)"""
    q = p['bc'] + p['nbc'] * 4 + p['nuv'] * 2
    spans = []
    for i in range(len(p['kgc'])):
        s = q
        t, q = ljdis.uleb(d, q)
        if t >= 5: q += t - 5
        elif t == 1:
            na, q = ljdis.uleb(d, q); nh, q = ljdis.uleb(d, q)
            for _ in range(na): _, q = ljdis.ktabk(d, q)
            for _ in range(2 * nh): _, q = ljdis.ktabk(d, q)
        spans.append((s, q))
    return spans

def header_spans(d, p):
    """offsets of the uleb counts numkgc inside the proto header"""
    q = p['body'] + 4
    a = q; nkgc, q = ljdis.uleb(d, q)
    return a, q, nkgc

def rebuild(d, p, new_body):
    return uleb_enc(len(new_body)) + new_body


def replace_script(raw, s, blen, vsize, new_bc):
    """puts a changed script back into the bundle data (its variant size and bytecode length grow with it)"""
    grow = len(new_bc) - blen
    return (bytearray(raw[:s - 16]) + struct.pack('<I', vsize + grow) + raw[s - 12:s - 8] + struct.pack('<I', blen + grow) +
            raw[s - 4:s] + new_bc + raw[s + blen:])


def patch_prompts(raw):
    """lua/components/interactor_component.lua, proto 16 (get_visual_for_button), gamepad branch: the PS4-only
    'remote play' remap (instructions 146-154) becomes   r3 = ({a="b", b="a", x="y", y="x"})[r3] or r3
    so the button pictures show the letters printed at those places on an AYN Odin (A right, B bottom, X top,
    Y left). Only the pictures change; what the buttons do stays the same."""
    s = raw.find(b"\x1bLJ\x01\x00(@lua/components/interactor_component.lua")
    assert s > 0
    vsize, = struct.unpack_from('<I', raw, s - 16); blen, = struct.unpack_from('<I', raw, s - 8)
    assert vsize == blen + 8
    bc = bytes(raw[s:s + blen]); ps = ljdis.parse(bc); p = ps[16]; n = len(p['kgc'])
    def k(i):
        op, A, B, C, D = ljdis.ins(bc, p, i)
        name = p['kgc'][n - 1 - D] if op in ('GGET', 'KSTR') else p['kgc'][n - 1 - C] if op == 'TGETS' else None
        return op, A, B, C, D, name
    want = [('GGET', 'IS_PS4'), ('ISF', None), ('JMP', None), ('GGET', 'ControllerSettings'), ('TGETS', 'remote_play_remap_menu_key'),
            ('MOV', None), ('MOV', None), ('CALL', None), ('MOV', None)]
    for j, (op, name) in enumerate(want):
        got = k(146 + j); assert got[0] == op and (name is None or got[5] == name), (146 + j, got)
    assert k(154)[1] == 3 and k(154)[4] == 7 and k(152)[4] == 3          # r3 = f(r2, r3)
    O = ljdis.OPS.index
    code = [bytes([O('TDUP'), 7]) + struct.pack('<H', n),                # r7 = {a="b",...}  (new constant, index n)
            bytes([O('TGETV'), 7, 3, 7]),                                 # r7 = r7[r3]
            bytes([O('ISTC'), 3]) + struct.pack('<H', 7),                 # if r7 then r3 = r7
            bytes([O('JMP'), 8]) + struct.pack('<H', 0x8000)] + [bytes([O('MOV'), 7, 7, 0])] * 5
    out = bytearray(bc)
    a = p['bc'] + 4 * 146; out[a:a + 36] = b''.join(code)
    def kstr(t):
        t = t.encode(); return uleb_enc(5 + len(t)) + t
    tab = uleb_enc(1) + uleb_enc(0) + uleb_enc(4) + b''.join(kstr(x) + kstr(y) for x, y in (('a', 'b'), ('b', 'a'), ('x', 'y'), ('y', 'x')))
    h0, h1, nk = header_spans(bc, p); assert nk == n
    k0 = kgc_spans(bc, p)[0][0]
    body = bytes(out[p['body']:h0]) + uleb_enc(n + 1) + bytes(out[h1:k0]) + tab + bytes(out[k0:p['end']])
    new_bc = bytes(out[:p['start']]) + rebuild(bc, p, body) + bytes(out[p['end']:])
    chk = ljdis.parse(new_bc)
    assert len(chk) == len(ps) and chk[16]['kgc'][0][2] == {'a': 'b', 'b': 'a', 'x': 'y', 'y': 'x'}
    return replace_script(raw, s, blen, vsize, new_bc)


def write_bundle(raw, path):
    """bundle file: header + 64 KB zlib chunks"""
    src = open(SRC, 'rb').read()
    ver, size, pad = struct.unpack_from('<III', src, 0)
    o = bytearray(struct.pack('<III', ver, len(raw), pad))
    for i in range(0, len(raw), 65536):
        c = bytes(raw[i:i + 65536]); z = zlib.compress(c, 9)
        if len(z) >= 65536: z = c + b'\0' * (65536 - len(c))
        o += struct.pack('<I', len(z)) + z
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(o)
    assert bdl.unpack(path) == bytes(raw)
    return len(o)

def main():
    raw = bdl.unpack(SRC)
    s = raw.find(b"\x1bLJ\x01\x00\x1e@lua/menu/screen_main_menu.lua")
    assert s > 0
    vsize, = struct.unpack_from('<I', raw, s - 16); blen, = struct.unpack_from('<I', raw, s - 8)
    assert vsize == blen + 8
    bc = raw[s:s + blen]
    ps = ljdis.parse(bc)
    p29, p30 = ps[29], ps[30]
    n29 = len(p29['kgc'])
    out = bytearray(bc)
    # --- proto 29: no-op inserts (MOV 8,8 instead of CALL 8,1,3)
    CALL_INS = bytes([ljdis.OPS.index('CALL'), 8, 3, 1])
    NOP = bytes([ljdis.OPS.index('MOV'), 8, 8, 0])
    def tdup_id(i):
        op, A, B, C, D = ljdis.ins(bc, p29, i); assert op == 'TDUP'
        return p29['kgc'][n29 - 1 - D][2].get('id', p29['kgc'][n29 - 1 - D][2].get('bg_img'))
    removed = []
    for tdup, call in ((53, 71), (75, 93), (97, 100), (423, 441)):
        a = p29['bc'] + 4 * call
        assert bc[a:a + 4] == CALL_INS, (call, bc[a:a + 4].hex())
        removed.append(tdup_id(tdup)); out[a:a + 4] = NOP
    assert removed == ['play_online', 'quick_join', 'menu_standard_divider_horizontal_gradient_dark', 'leaderboards'], removed
    # --- proto 29: the Local Game button shows the game's own "Start Game" text
    for i in (106, 118):
        op, A, B, C, D = ljdis.ins(bc, p29, i)
        assert op == 'KSTR' and p29['kgc'][n29 - 1 - D] == 'loc_menu_main_local_game', (i, op)
        op2, _, _, _, D2 = ljdis.ins(bc, p29, 55)
        assert op2 == 'KSTR' and p29['kgc'][n29 - 1 - D2] == 'loc_menu_lobby_start_game'
        a = p29['bc'] + 4 * i; out[a + 2:a + 4] = struct.pack('<H', D2)
    # --- proto 29: play_offline template gets selected = true
    op, A, B, C, D = ljdis.ins(bc, p29, 104); assert op == 'TDUP'
    ki = n29 - 1 - D; assert p29['kgc'][ki][2].get('id') == 'play_offline'
    sp = kgc_spans(bc, p29)[ki]
    q = sp[0]; t, q = ljdis.uleb(bc, q); assert t == 1
    na, q = ljdis.uleb(bc, q); nh, q2 = ljdis.uleb(bc, q)
    new_tab = uleb_enc(1) + uleb_enc(na) + uleb_enc(nh + 1) + bytes(out[q2:sp[1]]) + uleb_enc(5 + 8) + b'selected' + uleb_enc(2)
    body29 = bytes(out[p29['body']:sp[0]]) + new_tab + bytes(out[sp[1]:p29['end']])
    # --- proto 30: 'play_offline' as a new string constant (first in file order = new highest index), KSTR uses it
    n30 = len(p30['kgc'])
    k113 = 113
    op, A, B, C, D = ljdis.ins(bc, p30, k113); assert op == 'KSTR' and p30['kgc'][n30 - 1 - D] == 'play_online'
    a = p30['bc'] + 4 * k113
    out[a + 2:a + 4] = struct.pack('<H', n30)          # D = new index
    h0, h1, nk = header_spans(bc, p30); assert nk == n30
    kstart = kgc_spans(bc, p30)[0][0]
    body30 = (bytes(out[p30['body']:h0]) + uleb_enc(nk + 1) + bytes(out[h1:kstart]) +
              uleb_enc(5 + 12) + b'play_offline' + bytes(out[kstart:p30['end']]))
    # --- proto 0 (init): screen_default_selection = {"top", "play_offline"} instead of play_online
    p0 = ps[0]
    i0 = [i for i, k in enumerate(p0['kgc']) if isinstance(k, tuple) and k[1] == [None, 'top', 'play_online']]
    assert len(i0) == 1
    sp0 = kgc_spans(bc, p0)[i0[0]]
    tab0 = uleb_enc(1) + uleb_enc(3) + uleb_enc(0) + uleb_enc(0) + uleb_enc(5 + 3) + b'top' + uleb_enc(5 + 12) + b'play_offline'
    body0 = bytes(out[p0['body']:sp0[0]]) + tab0 + bytes(out[sp0[1]:p0['end']])
    # --- reassemble the chunk: protos 0, 29 and 30 replaced, everything else as is
    new_bc = (bytes(out[:p0['start']]) + rebuild(bc, p0, body0) + bytes(out[p0['end']:p29['start']]) +
              rebuild(bc, p29, body29) + bytes(out[p29['end']:p30['start']]) +
              rebuild(bc, p30, body30) + bytes(out[p30['end']:]))
    chk = ljdis.parse(new_bc)
    assert len(chk) == len(ps) and chk[29]['kgc'][n29 - 1 - D]  # parses again
    assert chk[29]['kgc'][ki][2].get('selected') is True and chk[30]['kgc'][0] == 'play_offline' and chk[0]['kgc'][i0[0]][1] == [None, 'top', 'play_offline']
    new_raw = replace_script(raw, s, blen, vsize, new_bc)
    n1 = write_bundle(new_raw, OUT)                                       # menu only (Xbox letters)
    n2 = write_bundle(patch_prompts(new_raw), OUT_ODIN)                   # menu + Odin letters on the buttons
    print("patched: removed", removed, "; bytecode", len(bc), "->", len(new_bc), "; bundles", n1, n2)

if __name__ == '__main__':
    main()


def patch_join_prompt():
    """gui/avatar_loadout_ui.lua (bundle e4e607d799ef7acc): the 'Press (A) or Enter' join picture of players 2-4 is a
    fixed picture name; for the Odin letters it shows hud_button_b (same length, so nothing else moves)."""
    src = os.path.join(GAME, "contents", "e4e607d799ef7acc")
    raw = bytearray(bdl.unpack(src))
    s = raw.find(b"@gui/avatar_loadout_ui.lua"); assert s > 0
    e = raw.find(b"\x1bLJ", s)
    j = raw.find(b"hud_button_a", s, e); assert 0 < j < e
    assert b"gamepad_button" in raw[j - 40:j] and raw.find(b"hud_button_a", j + 1, e) < 0, raw[j - 40:j]
    raw[j:j + 12] = b"hud_button_b"
    global SRC
    keep = SRC; SRC = src
    try: n = write_bundle(raw, os.path.join(os.path.dirname(OUT_ODIN), "e4e607d799ef7acc"))
    finally: SRC = keep
    print("join prompt patched", n)


if __name__ == '__main__':
    patch_join_prompt()
