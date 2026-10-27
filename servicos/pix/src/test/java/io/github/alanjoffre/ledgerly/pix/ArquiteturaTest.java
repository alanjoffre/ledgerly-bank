package io.github.alanjoffre.ledgerly.pix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** As regras da arquitetura hexagonal do Pix, verificadas a cada build — as mesmas dos serviços Spring. */
@AnalyzeClasses(packages = "io.github.alanjoffre.ledgerly.pix", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMINIO = "..pix.dominio..";
    private static final String APLICACAO = "..pix.aplicacao..";
    private static final String ADAPTADORES = "..pix.adaptadores..";

    @ArchTest
    static final ArchRule dominioEhJavaPuro = classes()
            .that().resideInAPackage(DOMINIO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMINIO, "io.github.alanjoffre.ledgerly.nucleo..", "java..")
            .because("as regras de negócio não podem depender de framework nem de banco");

    @ArchTest
    static final ArchRule aplicacaoSoUsaInjecaoETransacao = classes()
            .that().resideInAPackage(APLICACAO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                    APLICACAO,
                    DOMINIO,
                    "io.github.alanjoffre.ledgerly.nucleo..",
                    "java..",
                    "jakarta.enterprise.context..",
                    "jakarta.transaction..")
            .because("a aplicação só usa as anotações padrão Jakarta de escopo e de transação, nada do Quarkus");

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
    static final ArchRule entradaNaoFalaComSaida = noClasses()
            .that().resideInAPackage("..adaptadores.entrada..")
            .should().dependOnClassesThat().resideInAPackage("..adaptadores.saida..")
            .because("a API chama casos de uso; quem fala com banco, razão e Redis são os adaptadores de saída");
}
