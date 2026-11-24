#!/usr/bin/env python3
"""Slot MQ vhost reconciler (dev window CLI): thin wrapper over the installer
single source installkit/mq_vhost.py (R40 — no second implementation).

Keeps the tools/ops/e2e_slot_stack.sh contract: reads this chain's env keys
(RABBITMQ_USERNAME/RABBITMQ_PASSWORD/MQ_VHOST/MQ_MGMT_HOST_PORT), stays
owner-scoped to the vhost named by MQ_VHOST, prints a short reconcile summary.
Exit 0 = vhost + user permissions in place.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "installkit"))
import mq_vhost  # noqa: E402  (delivery-face single source)


def main():
    ok, notes = mq_vhost.ensure_vhost()
    for line in notes:
        print(line)
    if not ok:
        print("MQ_VHOST_RECONCILE=FAIL", file=sys.stderr)
        return 1
    print("MQ_VHOST_RECONCILE=OK vhost=%s" % mq_vhost.resolve_vhost())
    return 0


if __name__ == "__main__":
    sys.exit(main())
