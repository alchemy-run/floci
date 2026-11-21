package io.floci.aws.services.greengrassv2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreengrassSemverTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "0.0.0;>=2.0.0 <3.0.0;false",
        "2.5.0;>=2.0.0 <3.0.0;true",
        "3.0.0;>=2.0.0 <3.0.0;false",
        "1.2.3;^1.0.0;true",
        "2.0.0;^1.0.0;false",
        "0.2.5;^0.2.3;true",
        "0.3.0;^0.2.3;false",
        "1.2.9;~1.2;true",
        "1.3.0;~1.2;false",
        "1.5.0;1.x;true",
        "1.5.0;1.0.0 - 1.5.0;true",
        "1.5.1;1.0.0 - 1.5.0;false",
        "1.0.0;*;true",
        "1.0.0-beta;*;false",
        "2.1.0;>=1.0.0 <2.0.0 || >=2.1.0;true",
        "1.0.0;>= 1.0.0;true",
        "1.9.9;>1.0 <=1.9.9;true",
        "2.0.0-rc.1;>=2.0.0-rc.0 <3.0.0;true",
        "2.0.0-rc.1;>=1.0.0 <3.0.0;false",
    })
    void requirements(String version, String requirement, boolean satisfied) {
        assertEquals(satisfied, GreengrassSemver.satisfies(version, requirement));
    }
}
