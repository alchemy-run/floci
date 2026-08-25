package io.github.hectorvent.floci.services.databrew;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBrewRoutingFilterTest {

    private static final String DATABREW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/databrew/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesDataBrewCredentialScope() {
        assertTrue(DataBrewRoutingFilter.isDataBrew(DATABREW_AUTH));
        assertFalse(DataBrewRoutingFilter.isDataBrew(S3_AUTH));
        assertFalse(DataBrewRoutingFilter.isDataBrew(null));
        assertFalse(DataBrewRoutingFilter.isDataBrew(""));
    }

    @Test
    void prefixesRecipeAndDatasetPathsAndStripsTrailingSlash() {
        assertEquals("/aws-databrew/recipes", DataBrewRoutingFilter.rewritePath("/recipes"));
        assertEquals("/aws-databrew/recipes", DataBrewRoutingFilter.rewritePath("/recipes/"));
        assertEquals("/aws-databrew/recipes/my-recipe",
                DataBrewRoutingFilter.rewritePath("/recipes/my-recipe"));
        assertEquals("/aws-databrew/datasets/my-dataset",
                DataBrewRoutingFilter.rewritePath("/datasets/my-dataset"));
        assertEquals("/aws-databrew/schedules", DataBrewRoutingFilter.rewritePath("/schedules"));
        assertEquals("/aws-databrew/schedules/nightly",
                DataBrewRoutingFilter.rewritePath("/schedules/nightly"));
    }

    @Test
    void prefixesJobPaths() {
        assertEquals("/aws-databrew/profileJobs", DataBrewRoutingFilter.rewritePath("/profileJobs"));
        assertEquals("/aws-databrew/recipeJobs", DataBrewRoutingFilter.rewritePath("/recipeJobs"));
        assertEquals("/aws-databrew/jobs/my-job", DataBrewRoutingFilter.rewritePath("/jobs/my-job"));
        assertEquals("/aws-databrew/jobs/my-job/jobRuns",
                DataBrewRoutingFilter.rewritePath("/jobs/my-job/jobRuns"));
        assertEquals("/aws-databrew/recipeVersions", DataBrewRoutingFilter.rewritePath("/recipeVersions"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:databrew:us-east-1:000000000000:recipe/r",
                DataBrewRoutingFilter.rewritePath(
                        "/tags/arn:aws:databrew:us-east-1:000000000000:recipe/r"));
        assertEquals("/aws-databrew/recipes/my-recipe",
                DataBrewRoutingFilter.rewritePath("/aws-databrew/recipes/my-recipe"));
    }
}
