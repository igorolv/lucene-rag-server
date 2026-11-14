# Webhooks

The billing system notifies external services about payment events.

## Delivery and retries

A webhook is delivered as an HTTP POST with a JSON body. When the receiver does not answer with a
2xx status within ten seconds, the delivery is retried five times with growing pauses.

## Signature

Every request carries an `X-Signature` header: an HMAC-SHA256 of the body with the shared secret.
Receivers must reject requests whose signature does not match.

```bash
# Not a heading: this line is inside a code block
curl -X POST https://example.com/hook
```
