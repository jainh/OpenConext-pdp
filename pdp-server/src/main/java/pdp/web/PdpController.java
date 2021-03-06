package pdp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import org.apache.openaz.xacml.api.*;
import org.apache.openaz.xacml.api.pdp.PDPEngine;
import org.apache.openaz.xacml.std.dom.DOMStructureException;
import org.apache.openaz.xacml.std.json.JSONRequest;
import org.apache.openaz.xacml.std.json.JSONResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.TaskUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import pdp.JsonMapper;
import pdp.PdpPolicyException;
import pdp.PolicyNotFoundException;
import pdp.access.FederatedUser;
import pdp.access.PolicyAccess;
import pdp.access.PolicyIdpAccessEnforcer;
import pdp.conflicts.PolicyConflictService;
import pdp.domain.*;
import pdp.mail.MailBox;
import pdp.repositories.PdpPolicyRepository;
import pdp.repositories.PdpPolicyViolationRepository;
import pdp.serviceregistry.ServiceRegistry;
import pdp.stats.StatsContext;
import pdp.stats.StatsContextHolder;
import pdp.util.StreamUtils;
import pdp.xacml.PDPEngineHolder;
import pdp.xacml.PdpPolicyDefinitionParser;
import pdp.xacml.PolicyTemplateEngine;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.StreamSupport.stream;
import static org.apache.openaz.xacml.api.Decision.DENY;
import static org.apache.openaz.xacml.api.Decision.INDETERMINATE;
import static org.springframework.web.bind.annotation.RequestMethod.*;
import static pdp.access.PolicyAccess.*;
import static pdp.util.StreamUtils.singletonCollector;
import static pdp.util.StreamUtils.singletonOptionalCollector;

@RestController
@RequestMapping(headers = {"Content-Type=application/json"}, produces = {"application/json"})
public class PdpController implements JsonMapper {

  private final static Logger LOG = LoggerFactory.getLogger(PdpController.class);

  private final PDPEngineHolder pdpEngineHolder;
  private final PdpPolicyViolationRepository pdpPolicyViolationRepository;
  private final PdpPolicyRepository pdpPolicyRepository;
  private final PolicyTemplateEngine policyTemplateEngine = new PolicyTemplateEngine();
  private final PdpPolicyDefinitionParser pdpPolicyDefinitionParser = new PdpPolicyDefinitionParser();
  private final PolicyConflictService policyConflictService = new PolicyConflictService();
  private final ServiceRegistry serviceRegistry;
  private final PolicyIdpAccessEnforcer policyIdpAccessEnforcer;
  private final PDPEngine playgroundPdpEngine;
  private final boolean cachePolicies;
  private final MailBox mailBox;

  // Can't be final as we need to swap this reference for reloading policies in production
  private volatile PDPEngine pdpEngine;

  @Autowired
  public PdpController(@Value("${period.policies.refresh.minutes}") int period,
                       @Value("${policies.cachePolicies}") boolean cachePolicies,
                       PdpPolicyViolationRepository pdpPolicyViolationRepository,
                       PdpPolicyRepository pdpPolicyRepository,
                       PDPEngineHolder pdpEngineHolder,
                       ServiceRegistry serviceRegistry,
                       MailBox mailBox) {
    this.cachePolicies = cachePolicies;
    this.pdpEngineHolder = pdpEngineHolder;
    this.playgroundPdpEngine = pdpEngineHolder.newPdpEngine(false, true);
    this.pdpEngine = pdpEngineHolder.newPdpEngine(cachePolicies, false);
    this.pdpPolicyViolationRepository = pdpPolicyViolationRepository;
    this.policyIdpAccessEnforcer = new PolicyIdpAccessEnforcer(serviceRegistry);
    this.pdpPolicyRepository = pdpPolicyRepository;
    this.serviceRegistry = serviceRegistry;
    this.mailBox = mailBox;


    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
        TaskUtils.decorateTaskWithErrorHandler(this::refreshPolicies, t -> LOG.error("Exception in refreshPolicies task", t), true),
        period, period, TimeUnit.MINUTES);
  }

  @RequestMapping(method = RequestMethod.POST, value = "/decide/policy")
  public String decide(@RequestBody String payload) throws Exception {
    return doDecide(payload, false);
  }

  @RequestMapping(method = RequestMethod.POST, value = "/internal/decide/policy")
  public String decideInternal(@RequestBody String payload) throws Exception {
    refreshPolicies();
    return doDecide(payload, true);
  }

  private String doDecide(String payload, boolean isPlayground) throws Exception {
    StatsContext stats = StatsContextHolder.getContext();

    long start = System.currentTimeMillis();
    LOG.debug("decide request: {}", payload);

    Request request = JSONRequest.load(payload);
    addStatsDetails(stats, request);

    Response pdpResponse = isPlayground ? playgroundPdpEngine.decide(request) : pdpEngine.decide(request);

    String response = JSONResponse.toString(pdpResponse, LOG.isDebugEnabled());

    long took = System.currentTimeMillis() - start;
    stats.setResponseTimeMs(took);
    LOG.debug("decide response: {} took: {} ms", response, took);

    Decision decision = reportPolicyViolation(pdpResponse, response, payload, isPlayground);
    stats.setDecision(decision.toString());
    return response;
  }

  @RequestMapping(method = OPTIONS, value = "/protected/policies")
  public ResponseEntity<Void> options(HttpServletResponse response) {
    response.setHeader(HttpHeaders.ALLOW, Joiner.on(",").join(ImmutableList.of(GET, POST, PUT, DELETE)));
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(method = GET, value = {"/internal/policies", "/protected/policies"})
  public List<PdpPolicyDefinition> policyDefinitions() {
    List<PdpPolicyDefinition> policies = stream(pdpPolicyRepository.findAll().spliterator(), false)
        .map(policy -> addEntityMetaData(addAccessRules(policy, pdpPolicyDefinitionParser.parse(policy)))).collect(toList());

    policies = policies.stream().filter(policy -> !policy.isServiceProviderInvalidOrMissing()).collect(toList());
    
    //can't use Formula - https://issues.jboss.org/browse/JBPAPP-6571
    List<Object[]> countPerPolicyId = pdpPolicyViolationRepository.findCountPerPolicyId();
    Map<Long, Long> countPerPolicyIdMap = countPerPolicyId.stream().collect(toMap((obj) -> (Long) obj[0], (obj) -> (Long) obj[1]));
    policies.forEach(policy -> policy.setNumberOfViolations(countPerPolicyIdMap.getOrDefault(policy.getId(), 0L).intValue()));

    List<Object[]> revisionCountPerId = pdpPolicyRepository.findRevisionCountPerId();
    Map<Number, Number> revisionCountPerIdMap = revisionCountPerId.stream().collect(toMap((obj) -> (Number) obj[0], (obj) -> (Number) obj[1]));
    policies.forEach(policy -> policy.setNumberOfRevisions(revisionCountPerIdMap.getOrDefault(policy.getId().intValue(), 0).intValue()));

    return policyIdpAccessEnforcer.filterPdpPolicies(policies);
  }

  @RequestMapping(method = GET, value = {"/internal/conflicts", "/protected/conflicts"})
  public Map<String, List<PdpPolicyDefinition>> conflicts() {
    List<PdpPolicyDefinition> policies = stream(pdpPolicyRepository.findAll().spliterator(), false)
        .map(policy -> addEntityMetaData(pdpPolicyDefinitionParser.parse(policy))).collect(toList());
        
    Map<String, List<PdpPolicyDefinition>> conflicts = policyConflictService.conflicts(policies);
    List<PdpPolicyDefinition> invalid = policies.stream().filter(policy -> policy.isServiceProviderInvalidOrMissing()).collect(toList());
    if (!invalid.isEmpty()) {
    	conflicts.put("Invalid", invalid);
    }
    return conflicts;
  }

  @RequestMapping(method = {PUT, POST}, value = {"/internal/policies", "/protected/policies"})
  public PdpPolicy createPdpPolicy(@RequestBody PdpPolicyDefinition pdpPolicyDefinition) throws DOMStructureException {
    String policyXml = policyTemplateEngine.createPolicyXml(pdpPolicyDefinition);
    //if this works then we know the input was correct
    pdpPolicyDefinitionParser.parsePolicy(policyXml);

    PdpPolicy policy;
    if (pdpPolicyDefinition.getId() != null) {
      PdpPolicy fromDB = findPolicyById(pdpPolicyDefinition.getId(), WRITE);
      policy = fromDB.getParentPolicy() != null ? fromDB.getParentPolicy() : fromDB;
      //Cascade.ALL
      PdpPolicy.revision(pdpPolicyDefinition.getName(), policy, policyXml, policyIdpAccessEnforcer.username(),
          policyIdpAccessEnforcer.authenticatingAuthority(), policyIdpAccessEnforcer.userDisplayName(), pdpPolicyDefinition.isActive());
    } else {
      policy = new PdpPolicy(policyXml, pdpPolicyDefinition.getName(), true, policyIdpAccessEnforcer.username(),
          policyIdpAccessEnforcer.authenticatingAuthority(), policyIdpAccessEnforcer.userDisplayName(), pdpPolicyDefinition.isActive());

      //this will throw an Exception if it is not allowed
      policyIdpAccessEnforcer.actionAllowed(policy, PolicyAccess.WRITE, pdpPolicyDefinition.getServiceProviderId(), pdpPolicyDefinition.getIdentityProviderIds());
    }

    try {
      PdpPolicy saved = pdpPolicyRepository.save(policy);
      LOG.info("{} PdpPolicy {}", policy.getId() != null ? "Updated" : "Created", saved.getPolicyXml());
      checkConflicts(pdpPolicyDefinition);
      return saved;
    } catch (DataIntegrityViolationException e) {
      if (e.getMessage().contains("pdp_policy_name_revision_unique")) {
        throw new PdpPolicyException("name", "Policy name must be unique. " + pdpPolicyDefinition.getName() + " is already taken");
      } else {
        throw e;
      }
    }
  }

  private void checkConflicts(PdpPolicyDefinition pdpPolicyDefinition) {
    Map<String, List<PdpPolicyDefinition>> conflicts = conflicts();
    Optional<EntityMetaData> entityMetaData = serviceRegistry.serviceProviderOptionalByEntityId(pdpPolicyDefinition.getServiceProviderId());
    if (entityMetaData.isPresent() && conflicts.containsKey(entityMetaData.get().getNameEn())) {
      this.mailBox.sendConflictsMail(conflicts);
    }
  }

  @RequestMapping(method = GET, value = {"/internal/policies/{id}", "/protected/policies/{id}"})
  public PdpPolicyDefinition policyDefinition(@PathVariable Long id) {
    return addEntityMetaData(pdpPolicyDefinitionParser.parse(findPolicyById(id, READ)));
  }

  @RequestMapping(method = DELETE, value = {"/internal/policies/{id}", "/protected/policies/{id}"})
  public void deletePdpPolicy(@PathVariable Long id) throws DOMStructureException {
    PdpPolicy policy = findPolicyById(id, PolicyAccess.WRITE);

    LOG.info("Deleting PdpPolicy {}", policy.getName());
    policy = policy.getParentPolicy() != null ? policy.getParentPolicy() : policy;
    pdpPolicyRepository.delete(policy);
  }

  @RequestMapping(method = GET, value = "/internal/default-policy")
  public PdpPolicyDefinition defaultPolicy() {
    return new PdpPolicyDefinition();
  }

  @RequestMapping(method = GET, value = "/internal/policies/sp")
  public List<PdpPolicyDefinition> policyDefinitionsByServiceProvider(@RequestParam String serviceProvider) {
    List<PdpPolicyDefinition> policies = policyDefinitions();

    List<PdpPolicyDefinition> filterBySp = stream(policies.spliterator(), false).filter(policy -> policy.getServiceProviderId().equals(serviceProvider)).collect(toList());

    return policyIdpAccessEnforcer.filterPdpPolicies(filterBySp);
  }

  @RequestMapping(method = GET, value = "/internal/violations")
  public Iterable<PdpPolicyViolation> violations() {
    Iterable<PdpPolicyViolation> violations = pdpPolicyViolationRepository.findAll();
    return policyIdpAccessEnforcer.filterViolations(violations);
  }

  @RequestMapping(method = GET, value = "/internal/violations/{id}")
  public Iterable<PdpPolicyViolation> violationsByPolicyId(@PathVariable Long id) {
    Set<PdpPolicyViolation> violations = findPolicyById(id, VIOLATIONS).getViolations();
    return policyIdpAccessEnforcer.filterViolations(violations);
  }

  @RequestMapping(method = GET, value = {"/internal/revisions/{id}", "/protected/revisions/{id}"})
  public List<PdpPolicyDefinition> revisionsByPolicyId(@PathVariable Long id) {
    PdpPolicy policy = findPolicyById(id, PolicyAccess.READ);
    PdpPolicy parent = (policy.getParentPolicy() != null ? policy.getParentPolicy() : policy);

    Set<PdpPolicy> policies = parent.getRevisions();
    policies.add(parent);

    return policies.stream().map(rev ->
        addEntityMetaData(addAccessRules(rev, pdpPolicyDefinitionParser.parse(rev)))).collect(toList());
  }

  @RequestMapping(method = GET, value = {"/internal/attributes", "/protected/attributes"})
  public List<JsonPolicyRequest.Attribute> allowedAttributes() throws IOException {
    InputStream inputStream = new ClassPathResource("xacml/attributes/allowed_attributes.json").getInputStream();
    CollectionType type = objectMapper.getTypeFactory().constructCollectionType(List.class, JsonPolicyRequest.Attribute.class);
    return objectMapper.readValue(inputStream, type);
  }

  @RequestMapping(method = GET, value = "/internal/saml-attributes")
  public List<JsonPolicyRequest.Attribute> allowedSamlAttributes() throws IOException {
    InputStream inputStream = new ClassPathResource("xacml/attributes/extra_saml_attributes.json").getInputStream();
    CollectionType type = objectMapper.getTypeFactory().constructCollectionType(List.class, JsonPolicyRequest.Attribute.class);
    List<JsonPolicyRequest.Attribute> attributes = objectMapper.readValue(inputStream, type);
    attributes.addAll(allowedAttributes());
    return attributes;
  }

  private PdpPolicy findPolicyById(Long id, PolicyAccess policyAccess) {
    PdpPolicy policy = pdpPolicyRepository.findOne(id);
    if (policy == null) {
      throw new PolicyNotFoundException("PdpPolicy with id " + id + " not found");
    }
    PdpPolicyDefinition definition = pdpPolicyDefinitionParser.parse(policy);
    //this will throw an Exception if it is not allowed
    policyIdpAccessEnforcer.actionAllowed(policy, policyAccess, definition.getServiceProviderId(), definition.getIdentityProviderIds());
    return policy;
  }

  private PdpPolicyDefinition addAccessRules(PdpPolicy policy, PdpPolicyDefinition pd) {
    boolean actionsAllowed = policyIdpAccessEnforcer.actionAllowedIndicator(policy, PolicyAccess.WRITE, pd.getServiceProviderId(), pd.getIdentityProviderIds());
    pd.setActionsAllowed(actionsAllowed);
    pd.setAuthenticatingAuthorityName(serviceRegistry.identityProviderByEntityId(policy.getAuthenticatingAuthority()).getNameEn());
    return pd;
  }

  private PdpPolicyDefinition addEntityMetaData(PdpPolicyDefinition pd) {
    Optional<EntityMetaData> sp = serviceRegistry.serviceProviderOptionalByEntityId(pd.getServiceProviderId());
    pd.setServiceProviderInvalidOrMissing(!sp.isPresent());
    if (sp.isPresent()) {
    	pd.setServiceProviderName(sp.get().getNameEn());
    	pd.setActivatedSr(sp.get().isPolicyEnforcementDecisionRequired());
    }
    pd.setIdentityProviderNames(serviceRegistry.identityProviderNames(pd.getIdentityProviderIds()));
    return pd;
  }

  @RequestMapping(method = GET, value = "internal/users/me")
  public FederatedUser user() {
    return (FederatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  private void addStatsDetails(StatsContext stats, Request request) {
    RequestAttributes req = request.getRequestAttributes().stream()
        .filter(ra -> ra.getCategory().getUri().toString().equals("urn:oasis:names:tc:xacml:3.0:attribute-category:resource"))
        .collect(singletonCollector());
    Collection<Attribute> attributes = req.getAttributes();
    stats.setIdentityProvider(getAttributeValue(attributes, "IDPentityID").orElse(""));
    stats.setServiceProvicer(getAttributeValue(attributes, "SPentityID").orElse(""));
  }

  private Optional<String> getAttributeValue(Collection<Attribute> attributes, String attributeId) {
    Optional<Attribute> attribute = attributes.stream().filter(attr -> attr.getAttributeId().getUri().toString().equals(attributeId)).collect(singletonOptionalCollector());
    return attribute.map(attr -> (String) attr.getValues().iterator().next().getValue());
  }

  private Decision reportPolicyViolation(Response pdpResponse, String response, String payload, boolean isPlayground) {
    Collection<Result> results = pdpResponse.getResults();

    List<Result> deniesOrIndeterminates = results.stream().filter(result ->
        result.getDecision().equals(DENY) || result.getDecision().equals(INDETERMINATE)).collect(toList());
    if (!CollectionUtils.isEmpty(deniesOrIndeterminates)) {
      Optional<IdReference> idReferenceOptional = getPolicyId(deniesOrIndeterminates);
      if (idReferenceOptional.isPresent()) {
        String policyId = idReferenceOptional.get().getId().stringValue();
        Optional<PdpPolicy> policyOptional = pdpPolicyRepository.findFirstByPolicyIdAndLatestRevision(policyId, true).stream().findFirst();
        if (policyOptional.isPresent()) {
          pdpPolicyViolationRepository.save(new PdpPolicyViolation(policyOptional.get(), payload, response, isPlayground));
        }
      }
    }
    return results.iterator().next().getDecision();
  }

  private Optional<IdReference> getPolicyId(List<Result> deniesOrIndeterminates) {
    Result result = deniesOrIndeterminates.get(0);
    Collection<IdReference> policyIdentifiers = result.getPolicyIdentifiers();
    Collection<IdReference> policySetIdentifiers = result.getPolicySetIdentifiers();
    return !CollectionUtils.isEmpty(policyIdentifiers) ?
        policyIdentifiers.stream().collect(singletonOptionalCollector()) :
        policySetIdentifiers.stream().collect(singletonOptionalCollector());
  }

  private void refreshPolicies() {
    LOG.info("Starting reloading policies");
    long start = System.currentTimeMillis();
    this.pdpEngine = pdpEngineHolder.newPdpEngine(cachePolicies, false);
    LOG.info("Finished reloading policies in {} ms", System.currentTimeMillis() - start);
  }

}
