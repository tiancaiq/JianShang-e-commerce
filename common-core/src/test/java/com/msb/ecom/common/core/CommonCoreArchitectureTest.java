package com.msb.ecom.common.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class CommonCoreArchitectureTest {

    private final JavaClasses classes =
            new ClassFileImporter().importPackages("com.msb.ecom.common.core");

    @Test
    void containsNoPersistenceOrSpringBusinessComponents() {
        noClasses().should().beAnnotatedWith("jakarta.persistence.Entity").check(classes);
        noClasses().should().beAnnotatedWith("org.springframework.stereotype.Repository").check(classes);
        noClasses().should().beAnnotatedWith("org.springframework.stereotype.Service").check(classes);
        noClasses().should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .check(classes);
    }
}
