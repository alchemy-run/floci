package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Delegated Detective administrator accounts for this account/region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetectiveOrg {

    private List<Administrator> administrators = new ArrayList<>();

    public DetectiveOrg() {
    }

    public List<Administrator> getAdministrators() {
        return administrators;
    }

    public void setAdministrators(List<Administrator> administrators) {
        this.administrators = administrators == null ? new ArrayList<>() : administrators;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Administrator {
        private String accountId;
        private String graphArn;
        private String delegationTime;

        public Administrator() {
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getGraphArn() {
            return graphArn;
        }

        public void setGraphArn(String graphArn) {
            this.graphArn = graphArn;
        }

        public String getDelegationTime() {
            return delegationTime;
        }

        public void setDelegationTime(String delegationTime) {
            this.delegationTime = delegationTime;
        }
    }
}
