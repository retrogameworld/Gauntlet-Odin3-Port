def murmur64(key, seed=0):
    m = 0xc6a4a7935bd1e995; r = 47; M = (1 << 64) - 1
    data = key.encode() if isinstance(key, str) else key
    h = (seed ^ (len(data) * m)) & M
    n = len(data) // 8
    for i in range(n):
        k = int.from_bytes(data[i*8:i*8+8], 'little')
        k = (k * m) & M; k ^= k >> r; k = (k * m) & M
        h ^= k; h = (h * m) & M
    rest = data[n*8:]
    if rest:
        h ^= int.from_bytes(rest, 'little'); h = (h * m) & M
    h ^= h >> r; h = (h * m) & M; h ^= h >> r
    return h
