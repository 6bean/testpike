package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JMeter JMX artifact renderer: the three supported pressure models must map
 * onto native JMeter elements in the exact XStream shape the engine loads
 * (property names and serialization form pinned by javap + a real
 * {@code jmeter -n -t} load, not by memory), and every model outside the
 * compiled set fails closed with human wording naming the model — an
 * unrendered shape must never degrade into a different traffic curve
 * silently.
 * <p>Structural contract (execution-side load failure, pinned root cause):
 * <ul>
 *   <li>PreciseThroughputTimer properties are the engine's real names
 *       (throughput/throughputPeriod/duration/exactLimit/allowedThroughputSurplus
 *       /randomSeed/batchSize/batchThreadDelay); doubleProp wrappers must NOT
 *       carry a {@code <class>} child — XStream rejects it on load;</li>
 *   <li>samplers nest inside each thread group's hashTree (siblings of the
 *       thread group never execute — the frames=0 defect);</li>
 *   <li>no inline ResultCollector (jmeter -n -l writes the JTL itself);</li>
 *   <li>URLs resolve into the sampler's protocol/domain/port/path slots
 *       instead of stuffing a full URL into path and hoping it works.</li>
 * </ul>
 * <p>Approximation disclosure (W-d ruling): JMeter approaches a target rate
 * with BSS smoothing instead of an exact arrival schedule, and a ramp is
 * rendered as piecewise-constant sub-legs — the rendered artifact itself
 * carries the approximation note, downstream report footers reuse the same
 * single-source text.
 */
class PerfJmxTemplateRenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "http://perf-target.local/api";

    /** CONSTANT_RPS: one steady-rate leg driven by a loadable precise timer. */
    @Test
    void constantRpsRendersLoadableTimerShape() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("targetRps", 50);
        config.put("durationSeconds", 120);
        String xml = JmxTemplate.render("CONSTANT_RPS", config, steps(2), BASE_URL);

        assertTrue(xml.contains("PreciseThroughputTimer"),
                "constant RPS must drive a precise throughput timer");
        assertTrue(xml.contains("<doubleProp><name>throughput</name><value>3000.0</value></doubleProp>"),
                "target rate = 50 rps * 60 per throughputPeriod, got: " + xml);
        assertTrue(xml.contains("<intProp name=\"throughputPeriod\">60</intProp>"),
                "timer period must be the engine property throughputPeriod");
        assertTrue(xml.contains("<longProp name=\"duration\">120</longProp>"),
                "timer duration must be the engine property duration (long)");
        // The unloadable legacy shape must be gone entirely:
        assertTrue(!xml.contains("<class>java.lang.Double</class>"),
                "doubleProp wrappers must not carry a class child (XStream load failure)");
        assertTrue(!xml.contains("test_length") && !xml.contains("initial_throughput_offset")
                        && !xml.contains("randomizationSeed") && !xml.contains("batched"),
                "fabricated timer property names must not appear");
        assertEquals(1, countOccurrences(xml, "<ThreadGroup "),
                "constant shape needs exactly one scheduled thread group");
    }

    /** RAMP: piecewise-constant sub-legs approximate the rise (disclosed). */
    @Test
    void rampApproximatesRiseWithMonotoneSubLegs() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("baseRps", 10);
        config.put("peakRps", 200);
        config.put("rampSeconds", 60);
        config.put("durationSeconds", 300);
        String xml = JmxTemplate.render("RAMP", config, steps(1), BASE_URL);

        List<Double> rates = timerThroughputs(xml);
        assertTrue(rates.size() >= 2, "a ramp must render stepped sub-legs, got: " + rates);
        assertTrue(rates.get(0) < rates.get(rates.size() - 1),
                "sub-leg rates must rise toward the peak, got: " + rates);
        assertTrue(rates.get(rates.size() - 1) >= 200 * 60.0 - 15 * 60.0,
                "the final sub-leg must sit at or above (its midpoint of) the peak, got: " + rates);
        assertEquals(300.0, legDurations(xml).stream().mapToDouble(Double::doubleValue).sum(), 0.001,
                "scheduled windows must tile the declared scenario duration");
    }

    /** SPIKE: the four segments come from the shared PerfSpikeSegments source. */
    @Test
    void spikeTilesFourSegmentsFromSharedSource() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("baseRps", 20);
        config.put("peakRps", 400);
        config.put("durationSeconds", 100);
        String xml = JmxTemplate.render("SPIKE", config, steps(1), BASE_URL);
        Document doc = parse(xml);
        List<Element> groups = threadGroups(doc);
        PerfSpikeSegments legs = PerfSpikeSegments.derive(100);
        assertEquals(100, legs.riseSeconds() + legs.holdSeconds()
                + legs.fallSeconds() + legs.tailSeconds(), "segment sum invariant");
        assertTrue(groups.size() >= 4,
                "spike tiles rise/hold/fall/tail (ramping segments as sub-legs), got " + groups.size());
        double total = groups.stream().mapToDouble(g -> Double.parseDouble(
                text(g, "ThreadGroup.duration"))).sum();
        assertEquals(100.0, total, 0.001, "scheduled windows must tile the full duration");
        double peak = timerThroughputs(xml).stream().mapToDouble(Double::doubleValue).max().orElse(0);
        assertEquals(400 * 60.0, peak, 60 * 60.0,
                "the hold segment must reach the declared peak rate per minute");
    }

    /** Samplers nest inside every leg's hashTree — never as thread-group siblings. */
    @Test
    void samplersNestInsideEveryLegTree() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("baseRps", 20);
        config.put("peakRps", 400);
        config.put("durationSeconds", 100);
        String xml = JmxTemplate.render("SPIKE", config, steps(2), BASE_URL);
        Document doc = parse(xml);

        assertTrue(!xml.contains("ResultCollector"),
                "no inline listener — jmeter -n -l writes the JTL itself");
        List<Element> groups = threadGroups(doc);
        for (Element tg : groups) {
            Element legTree = siblingHashTree(doc, tg);
            assertNotNull(legTree, "every thread group needs its hashTree");
            List<Element> children = childElements(legTree);
            assertTrue(children.stream().anyMatch(e -> "PreciseThroughputTimer".equals(e.getTagName())),
                    "each leg's tree holds its own throughput timer");
            assertEquals(2, children.stream()
                            .filter(e -> "HTTPSamplerProxy".equals(e.getTagName())).count(),
                    "every declared step must be executable from every leg, thread group: "
                            + tg.getAttribute("testname"));
        }
    }

    /** Step URLs resolve into the sampler slots protocol/domain/port/path. */
    @Test
    void stepUrlsResolveIntoSamplerSlots() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("targetRps", 5);
        config.put("durationSeconds", 60);
        ArrayNode steps = MAPPER.createArrayNode();
        steps.addObject().put("method", "GET").put("url", "/orders/1");
        steps.addObject().put("method", "POST")
                .put("url", "http://127.0.0.1:8899/api/login")
                .put("body", "{\"username\":\"u\"}");
        String xml = JmxTemplate.render("CONSTANT_RPS", config, steps, BASE_URL);
        Document doc = parse(xml);
        List<Element> samplers = elementsByTag(doc, "HTTPSamplerProxy");
        assertEquals(2, samplers.size());

        Element rel = samplers.get(0);
        assertEquals("perf-target.local", text(rel, "HTTPSampler.domain"),
                "a relative step path must be joined to the environment base url");
        assertEquals("http", text(rel, "HTTPSampler.protocol"));
        // Join semantics: the base url's own path prefix is part of the target
        // (gateway routing), so "/api" + "/orders/1" -> "/api/orders/1".
        assertEquals("/api/orders/1", text(rel, "HTTPSampler.path"));
        assertTrue(!text(rel, "HTTPSampler.path").contains("://"),
                "path slot must hold a path, not a full url");

        Element abs = samplers.get(1);
        assertEquals("127.0.0.1", text(abs, "HTTPSampler.domain"));
        assertEquals("8899", text(abs, "HTTPSampler.port"),
                "explicit ports must land in the port slot");
        assertEquals("/api/login", text(abs, "HTTPSampler.path"));
        assertTrue(xml.contains("BodyARG"),
                "a declared body must travel as the raw-body argument");
    }

    /** A malformed target address fails closed naming the offending value. */
    @Test
    void unresolvableTargetFailsClosedNamingIt() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("targetRps", 5);
        config.put("durationSeconds", 60);
        ArrayNode steps = MAPPER.createArrayNode();
        steps.addObject().put("method", "GET").put("url", "not a url at all");
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> JmxTemplate.render("CONSTANT_RPS", config, steps, BASE_URL));
        assertTrue(rejected.getMessage().contains("not a url at all"),
                "wording must name the offending url, got: " + rejected.getMessage());
    }

    /** A model JMeter cannot truthfully render fails closed, naming the model. */
    @Test
    void uncompiledModelFailsClosedNamingIt() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("startRps", 10);
        config.put("stepRps", 5);
        config.put("steps", 3);
        config.put("durationSeconds", 60);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> JmxTemplate.render("LADDER", config, steps(1), BASE_URL));
        assertTrue(rejected.getMessage().contains("LADDER"),
                "wording must name the rejected model, got: " + rejected.getMessage());
    }

    /** Approximation disclosure travels inside the artifact (single-source note). */
    @Test
    void approximationNoteIsEmbeddedInArtifact() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("targetRps", 5);
        config.put("durationSeconds", 60);
        String xml = JmxTemplate.render("CONSTANT_RPS", config, steps(1), BASE_URL);
        assertTrue(xml.contains(JmxTemplate.APPROXIMATION_NOTE),
                "the JMX must embed the rate-approximation disclosure verbatim");
        assertTrue(xml.contains("PreciseThroughputTimer"),
                "disclosure names the timer that only approximates the target rate");
    }

    private static ArrayNode steps(int count) {
        ArrayNode steps = MAPPER.createArrayNode();
        for (int i = 1; i <= count; i++) {
            ObjectNode step = steps.addObject();
            step.put("method", "GET");
            step.put("url", "/orders/" + i);
        }
        return steps;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("rendered artifact must be well-formed XML: " + e, e);
        }
    }

    private static List<Element> elementsByTag(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static List<Element> threadGroups(Document doc) {
        return elementsByTag(doc, "ThreadGroup");
    }

    /** The hashTree element that follows a given element among its siblings. */
    private static Element siblingHashTree(Document doc, Element anchor) {
        NodeList candidates = doc.getElementsByTagName("hashTree");
        for (int i = 0; i < candidates.getLength(); i++) {
            Element cand = (Element) candidates.item(i);
            Node prev = cand.getPreviousSibling();
            while (prev != null && !(prev instanceof Element)) {
                prev = prev.getPreviousSibling();
            }
            if (prev == anchor) {
                return cand;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    /** Text content of a named-property child inside one element. */
    private static String text(Element owner, String propName) {
        for (Element child : childElements(owner)) {
            if (propName.equals(child.getAttribute("name"))) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static List<Double> timerThroughputs(String xml) {
        List<Double> out = new ArrayList<>();
        for (Element t : elementsByTag(parse(xml), "PreciseThroughputTimer")) {
            for (Element child : childElements(t)) {
                if ("doubleProp".equals(child.getTagName())) {
                    List<Element> parts = childElements(child);
                    if ("throughput".equals(parts.get(0).getTextContent().trim())) {
                        out.add(Double.parseDouble(parts.get(1).getTextContent().trim()));
                    }
                }
            }
        }
        return out;
    }

    private static List<Double> legDurations(String xml) {
        List<Double> out = new ArrayList<>();
        for (Element tg : threadGroups(parse(xml))) {
            out.add(Double.parseDouble(text(tg, "ThreadGroup.duration")));
        }
        return out;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
