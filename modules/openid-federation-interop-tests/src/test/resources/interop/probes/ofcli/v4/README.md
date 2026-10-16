# OFCLI structural probe recipe v4

Successor to v3; keeps the OFCLI source commit, pinned base image, locked runtime and source archive unchanged. The bounded wrapper replaces the unrelated-host negative control with a structural control using the already served starting entity as requested anchor. The probe remains structural-only; it does not call or claim OFCLI signature verification.

The positive run discovers the full fixture path first. The control fetches the same starting Entity Configuration, requests its own entity ID as the anchor, and expects the pinned no-chain marker because the starting node has no subordinate edge below it. This does not prove anchor trustworthiness or authorization. The peer chain remains independently verified by Nimbus before the probe runs.

This candidate has not been built or run. Source archive provenance and transport limitations remain as documented in the preceding v3 recipe.
