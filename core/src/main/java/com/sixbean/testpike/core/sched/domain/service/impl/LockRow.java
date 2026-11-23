package com.sixbean.testpike.core.sched.domain.service.impl;


record LockRow(String lockedBy, String lockedAt, String lockUntil, boolean running) {
}
