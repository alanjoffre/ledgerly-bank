package io.github.alanjoffre.ledgerly.contas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** As mesmas regras hexagonais do razão, aplicadas ao serviço de contas. */
@AnalyzeClasses(packages = "io.github.alanjoffre.ledgerly.contas", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMINIO = "..contas.dominio..";
    private static final String APLICACAO = "..contas.aplicacao..";
    private static final String ADAPTADORES = "..contas.adaptadores..";

    @ArchTest
    static final ArchRule dominioEhJavaPuro = classes()
            .that().resideInAPackage(DOMINIO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMINIO, "io.github.alanjoffre.ledgerly.nucleo..", "java..")
            .because("as regras de negócio não podem depender de JPA, Spring ou HTTP");

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
                    "org.springframework.transaction.annotation..");

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
    static final ArchRule entidadesJpaNaoSaemDaPersistencia = noClasses()
            .that().resideOutsideOfPackage("..adaptadores.saida.persistencia..")
            .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
            .because("a entidade JPA é detalhe do banco; o resto do sistema usa o domínio");
}
