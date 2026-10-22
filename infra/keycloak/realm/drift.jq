# Phần cấu hình realm werwa mà keycloak.sh giữ cố định. Áp cho cả bản dựng từ werwa-realm.json
# lẫn bản partial-export của máy chủ; hai kết quả phải trùng nhau, lệch thì keycloak.sh dừng.
# Không so id, bí mật, khóa ký và các phần Keycloak tự sinh.

def names: [.[]? | .name] | sort;
def mappers: [.protocolMappers[]? | {name, protocolMapper, config}] | sort_by(.name);
def userprofile: .components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0] | fromjson;

{
  realm: {
    realm, enabled, sslRequired, registrationAllowed, resetPasswordAllowed, rememberMe,
    editUsernameAllowed, duplicateEmailsAllowed,
    accessTokenLifespan, ssoSessionIdleTimeout, ssoSessionMaxLifespan,
    passwordPolicy, bruteForceProtected, permanentLockout, failureFactor,
    waitIncrementSeconds, maxFailureWaitSeconds, maxDeltaTimeSeconds,
    otpPolicyType, otpPolicyAlgorithm, otpPolicyDigits, otpPolicyPeriod,
    otpPolicyLookAheadWindow, otpPolicyCodeReusable,
    eventsEnabled, eventsExpiration, adminEventsEnabled, adminEventsDetailsEnabled,
    browserFlow, directGrantFlow
  },
  # Vai nghiệp vụ: mọi realm role trừ ba vai Keycloak tự tạo.
  roles: ([.roles.realm[] | select(.name | IN("default-roles-werwa", "offline_access", "uma_authorization") | not) | .name] | sort),
  defaultRole: .defaultRole.name,
  defaultRoleComposites: ([.roles.realm[] | select(.name == "default-roles-werwa") | .composites // {}
    | (.realm // [])[], ((.client // {}) | to_entries[] | .key + ":" + .value[])] | sort),
  scopeMappings: ([.scopeMappings[]? | select(.client) | {client, roles: (.roles | sort)}] | sort_by(.client)),
  clients: ([.clients[] | select(.clientId | startswith("werwa-")) | {
    clientId, enabled, publicClient, bearerOnly, standardFlowEnabled, implicitFlowEnabled,
    directAccessGrantsEnabled, serviceAccountsEnabled, fullScopeAllowed,
    frontchannelLogout: (.frontchannelLogout // false),
    redirectUris: ((.redirectUris // []) | sort), webOrigins: ((.webOrigins // []) | sort),
    pkce: .attributes["pkce.code.challenge.method"],
    defaultClientScopes: ((.defaultClientScopes // []) | sort),
    optionalClientScopes: ((.optionalClientScopes // []) | sort),
    mappers: mappers
  }] | sort_by(.clientId)),
  flows: ([.authenticationFlows[] | select(.alias | startswith("werwa ")) | {
    alias, providerId, topLevel,
    executions: ([.authenticationExecutions[] | {authenticator, flowAlias, requirement, priority}] | sort_by(.priority))
  }] | sort_by(.alias)),
  requiredActions: ([.requiredActions[] | {alias, enabled, defaultAction}] | sort_by(.alias)),
  userProfile: userprofile
}
