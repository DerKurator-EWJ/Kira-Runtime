# Kira v0.9.3 implementation note

The v0.9.2 Unified Memory repair made unfinished learning evidence persistent. v0.9.3 completes
the next architectural step: all promoted knowledge now follows one compiler/read contract.

Important compatibility rule: the existing indexed store format is unchanged. This version
rebuilds the new member index from stored macro compositions at load time, so existing v0.9.2.x
profiles do not need to be rewritten before use.
