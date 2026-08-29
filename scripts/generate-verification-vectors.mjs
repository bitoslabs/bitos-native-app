// Regenerates contracts/nostr/fixtures/verification-vectors.json.
//
// Vectors are produced by an INDEPENDENT implementation (nostr-tools 2.24.1
// on @noble/curves BIP-340) so the shared Kotlin codec is validated against
// something other than itself. The key below is a test-only fixture key and
// the outputs are public protocol vectors.
//
// Usage:
//   node scripts/generate-verification-vectors.mjs
//
// Requires the sibling web checkout with node_modules installed:
//   ../bitos-nostr-web/node_modules

import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const web = path.resolve(here, '../../bitos-nostr-web/node_modules');

const { finalizeEvent, serializeEvent } = await import(
  path.join(web, 'nostr-tools/lib/esm/pure.js')
);
const { schnorr } = await import(path.join(web, '@noble/curves/secp256k1.js'));

const sk = Buffer.from(
  'd2ad3c3c9e7b0f4f6a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718',
  'hex',
);
const pubkey = Buffer.from(schnorr.getPublicKey(sk)).toString('hex');

const vectors = [];
function record(name, event, valid) {
  vectors.push({
    name,
    valid,
    id: event.id,
    sig: event.sig,
    message: JSON.stringify(['EVENT', 'sub1', event]),
  });
}

// Deterministic signing: finalizeEvent uses a random aux, which would make
// every regeneration produce different signatures. BIP-340 nonces derive
// from (sk, aux, msg); a fixed zero aux makes vectors byte-reproducible.
const zeroAux = new Uint8Array(32);
function deterministicallySignedWith(secretKey, authorPubkey, event) {
  const withKey = { ...event, pubkey: authorPubkey };
  // serializeEvent returns the canonical NIP-01 string; hash it directly.
  const id = createHash('sha256').update(serializeEvent(withKey)).digest('hex');
  const sig = Buffer.from(schnorr.sign(hexToBytes(id), secretKey, zeroAux)).toString('hex');
  return { ...withKey, id, sig };
}
function deterministicallySigned(event) {
  return deterministicallySignedWith(sk, pubkey, event);
}

function hexToBytes(hex) {
  return Uint8Array.from(Buffer.from(hex, 'hex'));
}

const v1 = deterministicallySigned({
  kind: 1,
  created_at: 1710000000,
  tags: [['t', 'bitcoin']],
  content: 'gm from BitOS',
});
record('valid-text-note', v1, true);

const v2 = deterministicallySigned({
  kind: 0,
  created_at: 1710000100,
  tags: [],
  content: JSON.stringify({
    name: 'satoshi',
    display_name: 'Satoshi ₿',
    about: 'test vector',
  }),
});
record('valid-profile-metadata', v2, true);

const v3 = deterministicallySigned({
  kind: 1,
  created_at: 1710000200,
  tags: [
    ['e', 'b'.repeat(32)],
    ['p', 'c'.repeat(32)],
  ],
  content: 'line1\nline2 "quoted" ₿\u0007end',
});
record('valid-escaped-content', v3, true);

// Valid ID for tampered content, but the signature of the original V1 event.
const tampered = { ...v1, content: 'tampered but re-identified' };
tampered.id = createHash('sha256').update(serializeEvent(tampered)).digest('hex');
record('valid-id-wrong-signature', tampered, false);

// Valid event from a SECOND key: proves verification is per-pubkey.
const sk2 = Buffer.from(
  '4b1aa1a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d',
  'hex',
);
const pubkey2 = Buffer.from(schnorr.getPublicKey(sk2)).toString('hex');
const v4 = deterministicallySignedWith(sk2, pubkey2, {
  kind: 1,
  created_at: 1710000300,
  tags: [],
  content: 'second author note',
});
record('valid-second-key', v4, true);

// Kind-3 contact list from the primary key: follows the second key and G.
const gPub = 'f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9';
const v5 = deterministicallySigned({
  kind: 3,
  created_at: 1710000400,
  tags: [['p', pubkey2], ['p', gPub]],
  content: '',
});
record('valid-contact-list', v5, true);

// Valid event with one signature byte flipped.
const flippedSig = { ...v1 };
const sigBytes = Buffer.from(v1.sig, 'hex');
sigBytes[40] ^= 0x2a;
flippedSig.sig = sigBytes.toString('hex');
record('tampered-signature-byte', flippedSig, false);

// s := n (the group order): out of range for a valid signature.
const N_HEX =
  'fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141';
const sigSEqualsN = { ...v1, sig: v1.sig.slice(0, 64) + N_HEX };
record('signature-s-equals-n', sigSEqualsN, false);

// r := 0: structurally passes range checks but can never match R_x on curve.
const sigRZero = { ...v1, sig: '00'.repeat(32) + v1.sig.slice(64) };
record('signature-r-zero', sigRZero, false);

// Pubkey x with x^3+7 a non-residue mod p: lift_x must fail.
const P = 2n ** 256n - 2n ** 32n - 977n;
function powMod(base, exp, mod) {
  let r = 1n;
  base %= mod;
  while (exp > 0n) {
    if (exp & 1n) r = (r * base) % mod;
    base = (base * base) % mod;
    exp >>= 1n;
  }
  return r;
}
let nonResidueX = 0n;
for (let x = 1n; x < 200n; x++) {
  const c = (x * x * x + 7n) % P;
  if (powMod(c, (P - 1n) / 2n, P) === P - 1n) {
    nonResidueX = x;
    break;
  }
}
if (nonResidueX === 0n) throw new Error('no non-residue found');
const badPubkey = nonResidueX.toString(16).padStart(64, '0');
const notOnCurve = { ...v1, pubkey: badPubkey };
// Re-identify so the frame passes ID verification and fails at lift_x,
// exercising the pubkey trust stage rather than the hash stage.
notOnCurve.id = createHash('sha256').update(serializeEvent(notOnCurve)).digest('hex');
record('pubkey-not-on-curve', notOnCurve, false);

// FED-004: kind-22 short video with a NIP-92 imeta block carrying the
// rendition ladder (`fallbackrendition variant`) + mirrors (`fallback`).
// Locks the wire format both platform parsers and the bridge spec rows
// (`url|height|bitrate`) are fixture-tested against.
const v22 = deterministicallySigned({
  kind: 22,
  created_at: 1710000500,
  tags: [
    ['imeta', 'url https://cdn.example/v.mp4', 'm video/mp4', 'dim 1080x1920', 'duration 59'],
    ['imeta', 'url https://cdn.example/poster.jpg', 'm image/jpeg', 'dim 1080x1920'],
    ['imeta', 'fallback https://mirror.example/v.mp4'],
    ['imeta', 'fallbackrendition variant https://cdn.example/v-720.mp4 720x1280 2500000'],
    ['imeta', 'fallbackrendition variant https://cdn.example/v-480.mp4 480x854 1200000'],
  ],
  content: 'first bitz #bitcoin',
});
record('valid-kind22-rendition-ladder', v22, true);

const out = path.resolve(here, '../contracts/nostr/fixtures/verification-vectors.json');
await mkdir(path.dirname(out), { recursive: true });
await writeFile(out, JSON.stringify({ generator: 'nostr-tools 2.24.1 (@noble/curves)', pubkey, vectors }, null, 2) + '\n');

console.log(`pubkey: ${pubkey}`);
for (const x of vectors) {
  console.log(x.valid ? 'VALID  ' : 'INVALID', x.name, x.id);
}
console.log(`wrote ${path.relative(process.cwd(), out)}`);