package io.github.alanjoffre.ledgerly.assistente.adaptadores.saida.ajuda;

import io.github.alanjoffre.ledgerly.assistente.dominio.Trecho;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Os artigos de ajuda ({@code src/main/resources/ajuda/*.md}), cortados em trechos: um por seção
 * {@code ##}, com o título do artigo. Seção curta e de um assunto só acha melhor pela busca por
 * significado que o artigo inteiro.
 */
final class Artigos {

    private Artigos() {}

    static List<Trecho> ler(String padrao) {
        try {
            Resource[] arquivos = new PathMatchingResourcePatternResolver().getResources(padrao);
            Arrays.sort(arquivos, Comparator.comparing(Resource::getFilename));
            List<Trecho> trechos = new ArrayList<>();
            for (Resource arquivo : arquivos) {
                String nome = arquivo.getFilename().replaceFirst("\\.md$", "");
                trechos.addAll(cortar(nome, arquivo.getContentAsString(StandardCharsets.UTF_8)));
            }
            return trechos;
        } catch (IOException e) {
            throw new UncheckedIOException("Não deu para ler os artigos de ajuda em " + padrao, e);
        }
    }

    static List<Trecho> cortar(String artigo, String texto) {
        String[] linhas = texto.strip().split("\\R");
        String titulo = linhas[0].startsWith("# ") ? linhas[0].substring(2).strip() : artigo;
        List<Trecho> trechos = new ArrayList<>();
        StringBuilder secao = new StringBuilder();
        for (int i = linhas[0].startsWith("# ") ? 1 : 0; i < linhas.length; i++) {
            if (linhas[i].startsWith("## ")) {
                if (!secao.toString().isBlank()) {
                    trechos.add(new Trecho(artigo, titulo, secao.toString().strip()));
                }
                secao.setLength(0);
            }
            secao.append(linhas[i].startsWith("## ") ? linhas[i].substring(3) + ":" : linhas[i]).append('\n');
        }
        if (!secao.toString().isBlank()) {
            trechos.add(new Trecho(artigo, titulo, secao.toString().strip()));
        }
        return trechos;
    }

    /** Muda quando um artigo muda (ou o modelo de vetores): é a hora de indexar de novo. */
    static String versao(List<Trecho> trechos, String modeloDeVetores) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(modeloDeVetores.getBytes(StandardCharsets.UTF_8));
            for (Trecho t : trechos) {
                sha.update((t.artigo() + '\0' + t.titulo() + '\0' + t.texto() + '\0').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
