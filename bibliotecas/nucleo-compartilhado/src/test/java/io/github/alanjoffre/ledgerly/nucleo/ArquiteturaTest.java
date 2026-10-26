package io.github.alanjoffre.ledgerly.nucleo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Teste de arquitetura com ArchUnit: em vez de testar o comportamento, testa a
 * ESTRUTURA do código. Se alguém um dia importar Spring no núcleo compartilhado,
 * este teste falha e o build para — a regra deixa de depender da memória das pessoas.
 */
@AnalyzeClasses(
        packages = "io.github.alanjoffre.ledgerly.nucleo",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule nucleoDependeSoDoJava = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("io.github.alanjoffre.ledgerly.nucleo..", "java..")
            .because("o núcleo compartilhado precisa servir tanto a serviços Spring quanto Quarkus");
}
