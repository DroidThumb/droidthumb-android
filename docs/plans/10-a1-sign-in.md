<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# A1: sign-in and session (16-17 Oct phone test)

Rule: sign-in state comes from the server's answer, never timers or blind retries.

## A1a - session state from the server's answer
1. New AccountSessionState with exactly these states: Checking; SignedIn (with claimed = true and the device, or claimed = false); SignedOut; Unreachable(reason); ServerError(httpStatus).
2. The authoritative check: get a fresh Google id token silently (Credential Manager, no UI), then POST /v1/accounts/devices; signed in means the server accepted the token; claimed means this phone's derived device id is in the returned list (AccountViewModel.kt ~322-327). The silent fetch also repopulates the in-memory profile, so the avatar is right after process death.
3. Results: NoCredentialException from the silent fetch, or HTTP 401/403 from the server -> SignedOut. IOException of any kind (no network, DNS, TLS, timeout, connection refused) -> Unreachable with a short reason. Any other HTTP status -> ServerError(status). Any other exception -> Unreachable with the exception's class as the reason. AccountApiClient must stop collapsing these into one Failed("HTTP nnn"): add an error kind (Network / Rejected / Server).
4. When it runs: when Home starts, when the app resumes, after a sign-in completes, and when the user taps Retry. Only one check at a time: if one is in flight, a new trigger does not start another.
5. Home, the avatar menu and the This device card all read this one state. Copy:
   - Checking: "Checking your sign-in…"
   - SignedIn, claimed: the existing signed-in UI; This device shows the device.
   - SignedIn, not claimed: "This phone isn't linked to your account." button "Link this phone" (runs the claim, A1b).
   - SignedOut: "You're signed out." button "Sign in with Google".
   - Unreachable: "Can't reach the server: <reason>" button "Retry".
   - ServerError: "The server had a problem (HTTP <status>)." button "Retry".
   The old "Sign in to view this device" text goes.
6. Tests (fakes for the token source and the API): devices OK and listed -> SignedIn claimed; OK and not listed -> SignedIn not claimed; NoCredential -> SignedOut; 401 and 403 -> SignedOut; IOException -> Unreachable; 500 -> ServerError(500); a second trigger while a check is in flight starts nothing; copy mapping for each state.

## A1b - one-pass sign-in, claim on connection, no silent failures
1. Guard the whole sign-in chain (AccountViewModel.kt ~133-205): every step (Google credential, claim token, claim, device list) is caught. A failure shows its real reason in the UI (which step, and the reason) and is logged with the step name and the exception class and message. The state never stays on "Signing in" after a failure.
2. Cancellation (the coroutine cancelled while Google's sheet was in front, or the Activity went away): do not show it as an error; when the app is next in front, the A1a check runs and shows the real state.
3. Claim on connection: claiming goes over the WebSocket. If the transport is not Connected when the claim is needed, show "Connecting to the server…" and send the claim when the existing status StateFlow (DeviceTransportClient.kt ~110) next emits Connected: wait on that event, no timer, no polling. If the user has paused the connection (the app's Pause), do not wait: show "Your connection is paused. Resume it to link this phone." and send nothing. The server's claimed / claim_rejected reply decides; on claim_rejected show the server's reason. The existing bounded wait for the reply after sending stays; if it expires, show "The server didn't answer. Try again." with a button, and do not retry by itself.
4. After a successful claim, run the A1a check so the state comes from the server.
5. Logging: step-tagged lines (Logger.i for each step start and result, Logger.w for failures) in GoogleSignInClient, AccountApiClient (error kind plus HTTP status or exception class), the claim dispatch and its wait for Connected, and every AccountSessionState transition. Never tokens, emails, codes or screen content.
6. Tests: claim requested while Connecting, then Connected -> the claim is sent exactly once, after Connected; claim requested while paused -> the paused message and nothing sent; claim_rejected -> the server's reason shown; an exception thrown at each step -> an error state with that step's reason, never stuck in SigningIn; cancellation -> no error shown; a successful claim triggers the A1a check.
