package io.github.alanjoffre.ledgerly.razao;

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
@AnalyzeClasses(packages = "io.github.alanjoffre.ledgerly.razao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMINIO = "..razao.dominio..";
    private static final String APLICACAO = "..razao.aplicacao..";
    private static final String ADAPTADORES = "..razao.adaptadores..";

    @ArchTest
    static final ArchRule dominioEhJavaPuro = classes()
            .that().resideInAPackage(DOMINIO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMINIO, "io.github.alanjoffre.ledgerly.nucleo..", "java..")
            .because("as regras de negócio não podem depender de framework nem de banco");

    @ArchTest
    static final ArchRule aplicacaoSoUsaServiceETransactional = classes()
            .that().resideInAPackage(APLICACAO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    APLICACAO,
                    DOMINIO,
                    "io.github.alanjoffre.ledgerly.nucleo..",
                    "java..",
                    "org.springframework.stereotype..",
                    "org.springframework.transaction.annotation..")
            .because("a aplicação só pode usar @Service e @Transactional do Spring (ADR-0008)");

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
    static final ArchRule webNaoFalaComBanco = noClasses()
            .that().resideInAPackage("..adaptadores.entrada..")
            .should().dependOnClassesThat().resideInAPackage("..adaptadores.saida..")
            .because("a API chama casos de uso; quem fala com o banco são os adaptadores de saída");
}
