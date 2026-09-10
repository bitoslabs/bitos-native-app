# Android binding

Expose the stable C ABI through a narrow JNI adapter. This layer owns Android surfaces/buffers, coroutine cancellation bridging, thread attachment, and handle lifetime. No Compose, Nostr, or business rules belong here.
