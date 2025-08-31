/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostrads.protocol.types;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.tree.TreeNode;

import org.ngengine.platform.NGEPlatform;

/**
 * This class wraps the taxonomy CSV into a tree structure.
 * It provides methods for retrieving terms by their path or id.
 *
 * The taxonomy data can be loaded from a CSV file, but sometimes bundling
 * the complete taxonomy CSV is not desirable due to its size. To accommodate
 * this requirement, the class can be initialized with a null InputStream.
 *
 * When initialized without CSV data, the class will:
 * 1. Not load any predefined taxonomy structure
 * 2. Only allow retrieving terms by their id
 * 3. Create terms dynamically as they are requested
 *
 * Terms created without CSV data will have minimal metadata - only the id,
 * which will also serve as the name and path. This approach is primarily
 * useful for client applications where display metadata isn't critical,
 * but filtering functionality is still required.
 *
 * If for any reason the class is initialized with a valid input stream or with the default
 * constructor, but it fails to load the CSV data (eg. due to it not being bundled), it will
 * fall back to the same behavior as if it was initialized with a null InputStream.
 */
public final class AdTaxonomy implements Serializable {

    private static final Logger logger = Logger.getLogger(AdTaxonomy.class.getName());

    public static class Term implements Serializable {
        private final String id;
        private final String parent;
        private final String name;
        private final String tier1Name;
        private final String tier2Name;
        private final String tier3Name;
        private final String tier4Name;
        private final String path;
        private final String extension;

        public Term(
                String id,
                String parent,
                String name,
                String tier1Name,
                String tier2Name,
                String tier3Name,
                String tier4Name,
                String path,
                String extension) {
            this.id = id;
            this.parent = parent;
            this.name = name;
            this.tier1Name = tier1Name;
            this.tier2Name = tier2Name;
            this.tier3Name = tier3Name;
            this.tier4Name = tier4Name;
            this.path = path;
            this.extension = extension;
        }

        public String id() { return id; }
        public String parent() { return parent; }
        public String name() { return name; }
        public String tier1Name() { return tier1Name; }
        public String tier2Name() { return tier2Name; }
        public String tier3Name() { return tier3Name; }
        public String tier4Name() { return tier4Name; }
        public String path() { return path; }
        public String extension() { return extension; }

        @Override
        public String toString() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Term term = (Term) o;
            return Objects.equals(id, term.id) &&
                Objects.equals(parent, term.parent) &&
                Objects.equals(name, term.name) &&
                Objects.equals(tier1Name, term.tier1Name) &&
                Objects.equals(tier2Name, term.tier2Name) &&
                Objects.equals(tier3Name, term.tier3Name) &&
                Objects.equals(tier4Name, term.tier4Name) &&
                Objects.equals(path, term.path) &&
                Objects.equals(extension, term.extension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, parent, name, tier1Name, tier2Name, 
                                tier3Name, tier4Name, path, extension);
        }
    }

    private static class TreeNode implements Serializable {
        private final Term taxonomy;
        private final Map<String, TreeNode> children;

        public TreeNode(Term taxonomy, Map<String, TreeNode> children) {
            this.taxonomy = taxonomy;
            this.children = children;
        }

        public Term taxonomy() {
            return taxonomy;
        }

        public Map<String, TreeNode> children() {
            return children;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            TreeNode treeNode = (TreeNode) o;
            return Objects.equals(taxonomy, treeNode.taxonomy) &&
                    Objects.equals(children, treeNode.children);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taxonomy, children);
        }

        @Override
        public String toString() {
            return "TreeNode[" +
                    "taxonomy=" + taxonomy + ", " +
                    "children=" + children + ']';
        }
    }


    private final Map<String, TreeNode> taxonomyFlat = new HashMap<>();
    private final TreeNode taxonomyTree = new TreeNode(null, new HashMap<>());

    private boolean withCsv = false;

    public AdTaxonomy(InputStream csvIn) throws IOException {
        if (csvIn == null) {
            withCsv = false;
            logger.fine("Loaded without csv database");
            return;
        }

        try {
            loadCSV(csvIn);
            withCsv = true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Loaded without csv database", e);

            withCsv = false;
        }
    }

    public AdTaxonomy() {
        try {
            InputStream is = NGEPlatform.get().openResource("/org/ngengine/nostrads/taxonomy/nostr-content-taxonomy.csv");
            BufferedInputStream bis = new BufferedInputStream(is);
            loadCSV(bis);
            bis.close();
            withCsv = true;
        } catch (IOException e) {
            withCsv = false;
            logger.log(Level.WARNING, "Loaded without csv database", e);
        }
    }

    private void loadCSV(InputStream csvIn) throws IOException {
        byte[] bdata = csvIn.readAllBytes();
        String data = new String(bdata, StandardCharsets.UTF_8);
        StringTokenizer tokenizer = new StringTokenizer(data, "\n");

        if (tokenizer.hasMoreTokens()) tokenizer.nextToken();

        while (tokenizer.hasMoreTokens()) {
            String line = tokenizer.nextToken().trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // Skip empty lines and comments
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 8) throw new IllegalArgumentException(
                "Invalid CSV format: " + line + " " + parts.length + " parts found, expected 8"
            );

            String id = parts[0].trim();
            String parent = parts[1].trim();
            String name = parts[2].trim();
            String tier1 = parts[3].trim();
            String tier2 = parts[4].trim();
            String tier3 = parts[5].trim();
            String tier4 = parts[6].trim();
            String extension = parts[7].trim();

            Term taxonomy = new Term(
                id,
                parent,
                name,
                tier1,
                tier2,
                tier3,
                tier4,
                Stream
                    .of(tier1, tier2, tier3, tier4)
                    .filter(tier -> tier != null && !tier.isEmpty())
                    .collect(Collectors.joining("/")),
                extension
            );

            TreeNode parentNode = !parent.isEmpty() ? taxonomyFlat.get(parent) : null;
            if (parentNode == null) {
                parentNode = taxonomyTree; // Use root if no parent found
            }

            TreeNode node = new TreeNode(taxonomy, new HashMap<>());
            taxonomyFlat.put(id, node);
            parentNode.children.put(id, node);
        }
    }

    public Map<String, TreeNode> getTree() {
        return taxonomyTree.children;
    }

    public Term getByPath(String term) {
        if (withCsv) {
            for (TreeNode node : taxonomyFlat.values()) {
                if (node.taxonomy.path().equalsIgnoreCase(term)) {
                    return node.taxonomy;
                }
            }
        }
        return getById(term); // Fallback to getById if not found by path
    }

    public Term getById(String id) {
        if (!withCsv) {
            // if no CSV data is loaded, we can still return a Term with the id
            // we'll assume it is always a valid id and create a Term on the fly
            TreeNode node = taxonomyFlat.get(id);
            if (node == null) {
                Term term = new Term(id, null, id, id, null, null, null, id, null);
                node = new TreeNode(term, new HashMap<>());
            }
            return node.taxonomy;
        }
        TreeNode node = taxonomyFlat.get(id);
        if (node != null) {
            return node.taxonomy;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdTaxonomy)) return false;
        AdTaxonomy that = (AdTaxonomy) o;
        return taxonomyFlat.equals(that.taxonomyFlat);
    }

    @Override
    public int hashCode() {
        return taxonomyFlat.hashCode();
    }

    private String toString(TreeNode node, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(node.taxonomy.name).append(" (").append(node.taxonomy.id).append(")\n");
        for (TreeNode child : node.children.values()) {
            sb.append(toString(child, indent + "  "));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NostrContentTaxonomy:\n");
        for (TreeNode child : taxonomyTree.children.values()) {
            sb.append(toString(child, "  "));
        }

        return sb.toString();
    }
}
