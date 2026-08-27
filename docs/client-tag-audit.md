# BitOS client-tag audit

This read-only audit checks public Nostr kind-1 notes for the BitOS client tag:

```json
["client", "BitOS"]
```

It queries Nostr relays. It does not publish notes and does not require a private key.

## Create a user list

Create `users.txt` with one public key per line. Hex public keys and `npub` values are supported.
Comments beginning with `#` are ignored.

```text
# Test accounts
9f1a...64-character-hex-public-key
npub1...
```

Use complete keys in the real file; the shortened values above are only examples.

## Run the audit

From the project root:

```sh
./scripts/check-client-tags.sh --users users.txt
```

The equivalent npm command is:

```sh
npm run check:client-tags -- --users users.txt
```

Check one user:

```sh
./scripts/check-client-tags.sh --pubkey npub1...
```

To find all public kind-1 notes carrying the BitOS client tag across the relays:

```sh
./scripts/check-client-tags.sh --all --limit 5000
```

This mode discovers authors automatically and groups notes by user. The limit is the
maximum number of matching notes returned by the relays, so use a date range for large history.

## Filter by date

Dates may be ISO dates or Unix timestamps:

```sh
./scripts/check-client-tags.sh \
  --users users.txt \
  --since 2026-01-01 \
  --until 2026-08-10
```

Limit the number of notes checked per user:

```sh
./scripts/check-client-tags.sh --users users.txt --limit 500
```

## Use custom relays

The script uses the BitOS default read relays. Add one or more custom relays with `--relay`:

```sh
./scripts/check-client-tags.sh \
  --users users.txt \
  --relay wss://relay.damus.io \
  --relay wss://nos.lol
```

## Save JSON output

Use `--json` for reports that can be saved or processed by another script:

```sh
./scripts/check-client-tags.sh --users users.txt --json > client-tag-report.json
```

The JSON report also includes `summary.totalNotes`, which is the total number of
notes found across the requested users. Each user includes `notes`, `bitosTagged`,
`missingBitOsTag`, `coverage`, and `noteIdsMissingTag`.

## Limitations

Results depend on relay availability and retention. The audit checks kind-1 notes only;
metadata, reactions, messages, and other event kinds are not counted.
