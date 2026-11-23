package com.sixbean.testpike.api.cluster.infrastructure.remote.client;

import java.util.List;
import java.util.Map;


public interface CoreClusterEnrollClient {

    
    Map<String, Object> enroll(String token, String fingerprint);

       /**
        * Enroll heartbeat stamp (03 extension c): forwards step/stepStatus/message
        * trailing keys to the core five-parameter heartbeat (value gates and the
        * four-cell node-card projection live in core); step=null = old-form
        * accounting with zero injection.
        */
       default Map<String, Object> heartbeat(String token, String fingerprint, String step,
                                             String stepStatus, String message) {
           return heartbeat(token, fingerprint, step, stepStatus, message, null);
       }

       /**
 * Heartbeat + trailing components ledger : the
 * host service-agent's platform-family rows are forwarded as-is (List of
 * Map, zero re-shaping at this layer); empty/null = key omitted (old
 * reporters, zero drift).
 */
       Map<String, Object> heartbeat(String token, String fingerprint, String step,
                                     String stepStatus, String message, List<?> components);

 
    Map<String, Object> installkitConfig();

 
    Map<String, Object> engineRecipe(String code);

 
    List<Map<String, Object>> pollCommand(Map<String, Object> body);

 
    void progressCommand(Map<String, Object> body);

 
    void completeCommand(Map<String, Object> body);
}
