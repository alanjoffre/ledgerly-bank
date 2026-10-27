package io.github.alanjoffre.ledgerly.extrato;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * As regras da arquitetura hexagonal, verificadas a cada build. Quebrou a regra, o build falha.
 */
@AnalyzeClasses(packages = "io.github.alanjoffre.ledgerly.extrato", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMINIO = "..extrato.dominio..";
    private static final String APLICACAO = "..extrato.aplicacao..";
    private static final String ADAPTADORES = "..extrato.adaptadores..";

    @ArchTest
    static final ArchRule dominioEhJavaPuro = classes()
            .that().resideInAPackage(DOMINIO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMINIO, "io.github.alanjoffre.ledgerly.nucleo..", "java..")
            .because("as regras do extrato não podem depender de framework, do Kafka nem do MongoDB");

    @ArchTest
    static final ArchRule aplicacaoSoUsaService = classes()
            .that().resideInAPackage(APLICACAO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    APLICACAO,
                    DOMINIO,
                    "io.github.alanjoffre.ledgerly.nucleo..",
                    "java..",
                    "org.springframework.stereotype..")
            .because("a aplicação só pode usar @Service do Spring; Kafka e MongoDB ficam nos adaptadores");

    @ArchTest
    static final ArchRule camadas = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domínio").definedBy(DOMINIO)
            .layer("Aplicação").definedBy(APLICACAO)
            .layer("Adaptadores").definedBy(ADAPTADORES)
            .whereLayer("Adaptadores").mayNotBeAccessedByAnyLayer()
            .whereLayer("Aplicação").mayOnlyBeAccessedByLayers("Adaptadores")
            .whereLayer("Domínio").mayOnlyBeAccessedByLayers("Aplicação", "Adaptadores");

    @ArchTest
    static final ArchRule entradaNaoFalaComBanco = noClasses()
            .that().resideInAPackage("..adaptadores.entrada..")
            .should().dependOnClassesThat().resideInAPackage("..adaptadores.saida..")
            .because("a API e o consumidor chamam casos de uso; quem fala com o MongoDB são os adaptadores de saída");
}
