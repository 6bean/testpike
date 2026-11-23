package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.common.util.ActionJsonValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmPromptAssembler;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class GenComponentConfig {

    @Bean
    public CaseJsonParser caseJsonParser(ObjectMapper objectMapper) {
        return new CaseJsonParser(objectMapper);
    }

    /**
 * strict parser for the stage0 scenario list. Registered as a
 * stateless bean so the enumeration service and the later preview persistence
 * share one instance (no second copy).
 */
    @Bean
    public ScenarioListParser scenarioListParser(ObjectMapper objectMapper) {
        return new ScenarioListParser(objectMapper);
    }

    @Bean
    public SelfCheckValidator selfCheckValidator() {
        return new SelfCheckValidator();
    }

    /**
 * in-memory cosine over the embedding chain — the
 * repository's single similarity source, first consumer = the coverage
 * checker (a second consumer must reuse this bean per R40).
 */
    @Bean
    public SemanticMatcher semanticMatcher(EmbeddingFallbackChain chain,
                                           EmbeddingProperties properties) {
        return new SemanticMatcher(chain, properties);
    }

    /**
 * A5 coverage checker : semantic reconciliation when wired,
 * legacy fragment verdict when the chain is unavailable or the switch is
 * off — both collaborators are live beans, reads ride CoreGateway fresh.
 */
    @Bean
    public CoverageChecker coverageChecker(CoreGateway coreGateway, SemanticMatcher semanticMatcher) {
        return new CoverageChecker(coreGateway, semanticMatcher);
    }

    /**
 * segment B-2: per-item rule-ledger reconciler (anchored table-row
 * rules vs. the generated product text). Stateless bean so the
 * generation chain and any later consumer share one instance (R40);
 * the embedding source is the existing SemanticMatcher bean, no second
 * cosine implementation.
 */
    @Bean
    public RuleLedgerReconciler ruleLedgerReconciler(SemanticMatcher semanticMatcher) {
        return new RuleLedgerReconciler(new RuleItemExtractor(), semanticMatcher);
    }

    /**
 * faithfulness checker (claims of a generated case vs.
 * the original source blocks). Stateless bean so the generation chain and
 * the deliberation-ring checker share one instance (no second copy).
 */
    @Bean
    public FidelityGate fidelityGate() {
        return new FidelityGate();
    }

    /**
 * measured-confidence composite (fidelity / coverage /
 * source signals → the persisted 1/2/3 level). Stateless bean, one
 * instance for the whole generation chain.
 */
    @Bean
    public ConfidenceMeasurer confidenceMeasurer() {
        return new ConfidenceMeasurer();
    }

    @Bean
    public PriorityRule priorityRule() {
        return new PriorityRule();
    }

    @Bean
    public ActionJsonValidator actionJsonValidator() {
        return new ActionJsonValidator();
    }

    
    @Bean
    public ScriptLimitResolver scriptLimitResolver(CoreGateway coreGateway) {
        return new ScriptLimitResolver(coreGateway);
    }

    
    @Bean
    public PromptTemplateResolver promptTemplateResolver(CoreGateway coreGateway) {
        return new PromptTemplateResolver(coreGateway);
    }

    
    @Bean
    public LlmPromptAssembler llmPromptAssembler(TokenCounter tokenCounter) {
        return new LlmPromptAssembler(tokenCounter);
    }
}
