package io.github.alanjoffre.ledgerly.assistente;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * As regras da arquitetura hexagonal, verificadas a cada build. Aqui elas guardam uma coisa a mais:
 * o LangChain4j só entra nos adaptadores — trocar de biblioteca de IA não mexe nas travas.
 */
@AnalyzeClasses(packages = "io.github.alanjoffre.ledgerly.assistente", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String DOMINIO = "..assistente.dominio..";
    private static final String APLICACAO = "..assistente.aplicacao..";
    private static final String ADAPTADORES = "..assistente.adaptadores..";

    @ArchTest
    static final ArchRule dominioEhJavaPuro = classes()
            .that().resideInAPackage(DOMINIO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMINIO, "java..")
            .because("as travas não podem depender de framework nem do modelo de linguagem");

    @ArchTest
    static final ArchRule aplicacaoSoUsaService = classes()
            .that().resideInAPackage(APLICACAO)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(APLICACAO, DOMINIO, "java..", "org.springframework.stereotype..")
            .because("a aplicação só pode usar @Service do Spring; LangChain4j, HTTP e banco ficam nos adaptadores");

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
            .because("a API chama o caso de uso; quem fala com o modelo e com o extrato são os adaptadores de saída");
}
