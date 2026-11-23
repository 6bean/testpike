package com.sixbean.testpike.core.apiaccess.domain.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a stored pass-through door url into one this instance can actually answer.
 *
 * <p>Why this exists: a registry row stores an absolute url, and the port in it belongs to whichever
 * instance was running when the row was seeded. On a machine hosting more than one stack (a second
 * chain on its own slot, a probe instance, a container with a mapped port) dialing that stored
 * authority reaches a *different* service — which then answers about a catalog that is not the one
 * this caller is working against, and the failure looks like a bad request rather than a wrong
 * listener. Found exactly that way: a stack on its own port invoked the generic read tool and got an
 * HTTP 400 from the main checkout's service.
 *
 * <p>The correction happens at dial time and nowhere else. Rewriting the stored row would make the
 * two stacks overwrite each other's registry state on a shared database, and the last starter would
 * silently win; leaving the row alone keeps the registry as the single reviewed artifact while each
 * instance talks to itself.
 *
 * <p>Scope is the door paths of this feature only. A url aimed at another host, or at a path that is
 * not one of these doors, is somebody else's decision and is returned untouched.
 */
@Component
public class ApiAccessDoorAuthority {

    /** Path prefix of every door this service owns. */
    public static final String DOOR_PREFIX = "/internal/api-access/";

    private static final String LOCAL_AUTHORITY = "http://localhost:";

    private final int ownPort;

    public ApiAccessDoorAuthority(@Value("${server.port:8083}") int ownPort) {
        this.ownPort = ownPort;
    }

    /**
     * The url to dial: the stored one when it already points at this instance (or anywhere that is
     * not one of our own doors), the same path on this port when the authority drifted.
     *
     * @param storedUrl exec_spec url as registered
     */
    public String localize(String storedUrl) {
        String corrected = localize(storedUrl, ownPort);
        return corrected == null ? storedUrl : corrected;
    }

    /**
     * Pure form of the rule, exposed for tests: the rewritten url, or null when nothing should move.
     */
    public static String localize(String storedUrl, int ownPort) {
        if (storedUrl == null || !storedUrl.startsWith(LOCAL_AUTHORITY)) {
            return null;
        }
        String rest = storedUrl.substring(LOCAL_AUTHORITY.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.substring(slash).startsWith(DOOR_PREFIX)) {
            return null;
        }
        if (rest.substring(0, slash).equals(String.valueOf(ownPort))) {
            return null;
        }
        return LOCAL_AUTHORITY + ownPort + rest.substring(slash);
    }
}
