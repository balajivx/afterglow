"""End-to-end test of DnsFilterEngine.

1. Builds real DNS query packets (dnspython) wrapped in IPv4/UDP (scapy), plus junk packets.
2. Runs a real UDP DNS server on 127.0.0.1 that answers forwarded queries.
3. Runs the Java engine over the packets.
4. Validates every response with scapy (IP/UDP checksums, addresses, ports) and dnspython.
"""
import socket, struct, subprocess, sys, threading
import dns.message, dns.rdatatype, dns.flags, dns.rrset
from scapy.all import IP, UDP, TCP, raw

APP_IP, FAKE_DNS = "10.111.222.2", "10.111.222.1"
fails = []

def check(ok, what):
    if not ok:
        fails.append(what)
        print("FAIL:", what)

# ---------------- upstream DNS server -----------------
srv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
srv.bind(("127.0.0.1", 0))
PORT = srv.getsockname()[1]
upstream_seen = []

def serve():
    while True:
        data, addr = srv.recvfrom(4096)
        q = dns.message.from_wire(data)
        upstream_seen.append(str(q.question[0].name).rstrip("."))
        r = dns.message.make_response(q)
        qn = q.question[0]
        if qn.rdtype == dns.rdatatype.A:
            r.answer.append(dns.rrset.from_text(qn.name, 60, "IN", "A", "93.184.216.34"))
        elif qn.rdtype == dns.rdatatype.AAAA:
            r.answer.append(dns.rrset.from_text(qn.name, 60, "IN", "AAAA", "2606:2800:220:1::1"))
        elif qn.rdtype == dns.rdatatype.TXT:  # big response (~1.4 KB) to test sizes
            r.answer.append(dns.rrset.from_text(qn.name, 60, "IN", "TXT", *['"%s"' % ("x" * 200)] * 7))
        srv.sendto(r.to_wire(), addr)

threading.Thread(target=serve, daemon=True).start()

# ---------------- build inputs -----------------
cases = [  # (name, type, expect_blocked, use_edns)
    ("www.example.com", "A", False, True),
    ("www.example.com", "AAAA", False, True),
    ("www.youtube.com", "A", False, True),
    ("rr1---sn-ci5gup-qxae.googlevideo.com", "A", False, False),
    ("api.hotstar.com", "A", False, True),
    ("pubads.g.doubleclick.net", "A", False, True),       # video-compat allow
    ("big.example.org", "TXT", False, True),
    ("pagead2.googlesyndication.com", "A", True, True),
    ("pagead2.googlesyndication.com", "AAAA", True, True),
    ("PageAd2.GoogleSyndication.COM", "A", True, False),  # 0x20 case randomisation
    ("googleads.g.doubleclick.net", "HTTPS", True, True),
    ("ads.applovin.com", "A", True, True),
    ("app-measurement.com", "A", True, False),
    ("popads.net", "AAAA", True, True),
]
pkts, meta = [], []
for i, (name, t, blk, edns) in enumerate(cases):
    q = dns.message.make_query(name, t, use_edns=0 if edns else None)
    q.id = 1000 + i
    sport = 40000 + i
    p = IP(src=APP_IP, dst=FAKE_DNS, id=i) / UDP(sport=sport, dport=53) / raw(q.to_wire())
    pkts.append(raw(p))
    meta.append((name, t, blk, q.id, sport))

junk = [
    raw(IP(src=APP_IP, dst=FAKE_DNS) / TCP(sport=5555, dport=853, flags="S")),     # DoT probe
    raw(IP(src=APP_IP, dst=FAKE_DNS) / UDP(sport=5555, dport=443) / b"quic"),       # not DNS
    b"\x60" + b"\x00" * 60,                                                         # IPv6
    raw(IP(src=APP_IP, dst=FAKE_DNS) / UDP(sport=5555, dport=53) / b"\x00\x01"),    # truncated DNS
    raw(IP(src=APP_IP, dst=FAKE_DNS, flags="MF") / UDP(sport=5555, dport=53) / raw(dns.message.make_query("a.b", "A").to_wire())),
    b"\x45\x00",                                                                    # runt
]
with open("/tmp/in.bin", "wb") as f:
    for p in pkts + junk:
        f.write(struct.pack(">i", len(p)) + p)

# ---------------- run engine -----------------
r = subprocess.run(["java", "-cp", "out", "AdblockTest", "engine", "/home/claude/sdk/pro.txt",
                    "/tmp/in.bin", "/tmp/out.bin", str(PORT)], capture_output=True, text=True)
print(r.stdout.strip())
check(r.returncode == 0, "java engine run")

# ---------------- validate -----------------
out = open("/tmp/out.bin", "rb").read()
resp, o = [], 0
while o < len(out):
    (n,) = struct.unpack(">i", out[o:o + 4]); resp.append(out[o + 4:o + 4 + n]); o += 4 + n
check(len(resp) == len(cases), f"one response per query ({len(resp)} vs {len(cases)})")

by_port = {}
for b in resp:
    ip = IP(b)
    # checksums: let scapy recompute and compare with what we sent
    want_ip = IP(raw(ip.copy().__class__(raw(ip))))  # parse again
    re = ip.copy(); del re.chksum; del re[UDP].chksum
    re = IP(raw(re))
    check(ip.chksum == re.chksum, f"IPv4 checksum {ip.chksum:#x} vs {re.chksum:#x}")
    check(ip[UDP].chksum == re[UDP].chksum, f"UDP checksum {ip[UDP].chksum:#x} vs {re[UDP].chksum:#x}")
    check(ip.src == FAKE_DNS and ip.dst == APP_IP, "addresses swapped")
    check(ip.len == len(b) and ip[UDP].len == len(b) - 20, "length fields")
    check(ip[UDP].sport == 53, "src port 53")
    by_port[ip[UDP].dport] = dns.message.from_wire(bytes(ip[UDP].payload))

for name, t, blk, qid, sport in meta:
    m = by_port.get(sport)
    if m is None:
        check(False, f"no response for {name} {t}"); continue
    check(m.id == qid, f"{name}: id preserved")
    check(m.flags & dns.flags.QR, f"{name}: QR set")
    check(str(m.question[0].name).rstrip(".").lower() == name.lower(), f"{name}: question echoed")
    ans = [rd.to_text() for rr in m.answer for rd in rr]
    if blk:
        check(m.rcode() == 0, f"{name}: NOERROR")
        expect = {"A": ["0.0.0.0"], "AAAA": ["::"]}.get(t, [])
        check(ans == expect, f"{name} {t}: blocked answer {ans} != {expect}")
        check(name.lower() not in upstream_seen, f"{name}: must not reach upstream")
    else:
        check(len(ans) > 0, f"{name} {t}: forwarded answer present")
    print(f"  {'BLOCKED ' if blk else 'resolved'} {t:5} {name:40} -> {', '.join(a[:24] for a in ans) or '(no data)'}")

print(f"upstream received {len(upstream_seen)} queries (expected {sum(1 for c in cases if not c[2])})")
check(len(upstream_seen) == sum(1 for c in cases if not c[2]), "only allowed names forwarded")
print("dns_e2e:", "PASS" if not fails else f"{len(fails)} FAILURES")
sys.exit(1 if fails else 0)
