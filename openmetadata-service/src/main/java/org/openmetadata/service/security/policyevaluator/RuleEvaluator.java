package org.openmetadata.service.security.policyevaluator;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.Function;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.Entity;
import org.openmetadata.service.resources.tags.TagLabelCache;
import org.openmetadata.service.security.policyevaluator.SubjectContext.PolicyContext;

/**
 * Note that the methods in the class become available for SpEL expressions for authoring expressions such as
 * "noOwner()" or "!noOwner()"
 */
@Slf4j
public class RuleEvaluator {
  private final PolicyContext policyContext;
  private final SubjectContext subjectContext;
  private final ResourceContextInterface resourceContext;

  // When true, RuleEvaluator is only used for validating the expression and not for access control
  private final boolean expressionValidation;

  public RuleEvaluator() {
    this.policyContext = null;
    this.subjectContext = null;
    this.resourceContext = null;
    this.expressionValidation = true;
  }

  public RuleEvaluator(
      PolicyContext policyContext, SubjectContext subjectContext, ResourceContextInterface resourceContext) {
    this.policyContext = policyContext;
    this.subjectContext = subjectContext;
    this.resourceContext = resourceContext;
    this.expressionValidation = false;
  }

  @Function(
      name = "noOwner",
      input = "none",
      description = "Returns true if the entity being accessed has no owner",
      examples = {"noOwner()", "!noOwner", "noOwner() || isOwner()"})
  @SuppressWarnings("unused") // Used in SpelExpressions
  public boolean noOwner() throws IOException {
    if (expressionValidation) {
      return false;
    }
    return resourceContext != null && resourceContext.getOwner() == null;
  }

  @Function(
      name = "isOwner",
      input = "none",
      description = "Returns true if the logged in user is the owner of the entity being accessed",
      examples = {"isOwner()", "!isOwner", "noOwner() || isOwner()"})
  public boolean isOwner() throws IOException {
    if (expressionValidation) {
      return false;
    }
    if (subjectContext == null || resourceContext == null) {
      return false;
    }
    return subjectContext.isOwner(resourceContext.getOwner());
  }

  @Function(
      name = "matchAllTags",
      input = "List of comma separated tag or glossary fully qualified names",
      description = "Returns true if the entity being accessed has all the tags given as input",
      examples = {"matchAllTags('PersonalData.Personal', 'Tier.Tier1', 'Business Glossary.Clothing')"})
  @SuppressWarnings("ununsed")
  public boolean matchAllTags(String... tagFQNs) throws IOException {
    if (expressionValidation) {
      for (String tagFqn : tagFQNs) {
        TagLabelCache.getTag(tagFqn);
      }
      return false;
    }
    if (resourceContext == null) {
      return false;
    }
    List<TagLabel> tags = resourceContext.getTags();
    LOG.debug("matchAllTags {} resourceTags {}", Arrays.toString(tagFQNs), Arrays.toString(tags.toArray()));
    for (String tagFQN : tagFQNs) {
      TagLabel found = tags.stream().filter(t -> t.getTagFQN().equals(tagFQN)).findAny().orElse(null);
      if (found == null) {
        return false;
      }
    }
    return true;
  }

  @Function(
      name = "matchAnyTag",
      input = "List of comma separated tag or glossary fully qualified names",
      description = "Returns true if the entity being accessed has at least one of the tags given as input",
      examples = {"matchAnyTag('PersonalData.Personal', 'Tier.Tier1', 'Business Glossary.Clothing')"})
  @SuppressWarnings("unused") // Used in SpelExpressions
  public boolean matchAnyTag(String... tagFQNs) throws IOException {
    if (expressionValidation) {
      for (String tagFqn : tagFQNs) {
        TagLabelCache.getTag(tagFqn);
      }
      return false;
    }
    if (resourceContext == null) {
      return false;
    }
    List<TagLabel> tags = resourceContext.getTags();
    LOG.debug("matchAnyTag {} resourceTags {}", Arrays.toString(tagFQNs), Arrays.toString(tags.toArray()));
    for (String tagFQN : tagFQNs) {
      TagLabel found = tags.stream().filter(t -> t.getTagFQN().equals(tagFQN)).findAny().orElse(null);
      if (found != null) {
        return true;
      }
    }
    return false;
  }

  @Function(
      name = "matchTeam",
      input = "None",
      description =
          "Returns true if the user and the resource belongs to the team hierarchy where this policy is"
              + "attached. This allows restricting permissions to a resource to the members of the team hierarchy.",
      examples = {"matchTeam()"})
  @SuppressWarnings("unused") // Used in SpelExpressions
  public boolean matchTeam() throws IOException {
    if (expressionValidation) {
      return false;
    }
    if (resourceContext == null || resourceContext.getOwner() == null) {
      return false; // No ownership information
    }
    if (policyContext == null || !policyContext.getEntityType().equals(Entity.TEAM)) {
      return false; // Policy must be attached to a team for this function to work
    }
    return subjectContext.isTeamAsset(policyContext.getEntityName(), resourceContext.getOwner())
        && subjectContext.isUserUnderTeam(policyContext.getEntityName());
  }

  @Function(
      name = "inAnyTeam",
      input = "List of comma separated team names",
      description = "Returns true if the user belongs under the hierarchy of any of the teams in the given team list.",
      examples = {"inAnyTeam('marketing')"})
  @SuppressWarnings("unused") // Used in SpelExpressions
  public boolean inAnyTeam(String... teams) {
    if (expressionValidation) {
      for (String team : teams) {
        SubjectCache.getTeamByName(team);
      }
      return false;
    }
    if (subjectContext == null) {
      return false;
    }
    for (String team : teams) {
      if (subjectContext.isUserUnderTeam(team)) {
        LOG.debug("inAnyTeam - User {} is under the team {}", subjectContext.getUser().getName(), team);
        return true;
      }
      LOG.debug("inAnyTeam - User {} is not under the team {}", subjectContext.getUser().getName(), team);
    }
    return false;
  }

  @Function(
      name = "hasAnyRole",
      input = "List of comma separated roles",
      description =
          "Returns true if the user (either direct or inherited from the parent teams) has one or more roles "
              + "from the list.",
      examples = {"hasAnyRole('DataSteward', 'DataEngineer')"})
  @SuppressWarnings("unused") // Used in SpelExpressions
  public boolean hasAnyRole(String... roles) {
    if (expressionValidation) {
      for (String role : roles) {
        RoleCache.getRole(role);
      }
      return false;
    }
    if (subjectContext == null) {
      return false;
    }
    for (String role : roles) {
      if (subjectContext.hasAnyRole(role)) {
        LOG.debug("hasAnyRole - User {} has the role {}", subjectContext.getUser().getName(), role);
        return true;
      }
    }
    return false;
  }
}
