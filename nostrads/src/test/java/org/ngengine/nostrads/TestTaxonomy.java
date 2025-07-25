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

package org.ngengine.nostrads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.nostrads.protocol.types.AdTaxonomy.Term;

public class TestTaxonomy {

    @Test
    public void testLoadAndSerializeTaxonomy() throws IOException, ClassNotFoundException {
        AdTaxonomy taxonomy = new AdTaxonomy();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(taxonomy);

        byte[] serializedData = bos.toByteArray();
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serializedData));
        AdTaxonomy deserialized = (AdTaxonomy) ois.readObject();
        ois.close();

        assertEquals(taxonomy, deserialized);
    }

    @Test
    public void testGetTaxonomy() throws IOException {
        AdTaxonomy taxonomy = new AdTaxonomy();

        // by id
        Term found = taxonomy.getById("215");
        assertEquals("215", found.id());
        assertEquals("Food & Drink/Barbecues and Grilling", found.path());
        assertEquals("Food & Drink", found.tier1Name());
        assertEquals("Barbecues and Grilling", found.tier2Name());
        assertEquals("", found.tier3Name());
        assertEquals("", found.tier4Name());
        assertEquals("Barbecues and Grilling", found.name());
        assertEquals("", found.extension());

        // by term
        Term foundByTerm = taxonomy.getByPath("Food & Drink/Barbecues and Grilling");
        assertEquals(found, foundByTerm);
        assertEquals("215", foundByTerm.id());
        assertEquals("Food & Drink/Barbecues and Grilling", foundByTerm.path());
        assertEquals("Food & Drink", foundByTerm.tier1Name());
        assertEquals("Barbecues and Grilling", foundByTerm.tier2Name());
        assertEquals("", foundByTerm.tier3Name());
        assertEquals("", foundByTerm.tier4Name());
        assertEquals("Barbecues and Grilling", foundByTerm.name());
        assertEquals("", foundByTerm.extension());

        // not found
        Term notFound = taxonomy.getById("9999");
        assertTrue(notFound == null);
    }

    @Test
    public void testEquals() throws IOException {
        AdTaxonomy taxonomy = new AdTaxonomy();

        Term found = taxonomy.getById("215");
        assertEquals("215", found.id());
        assertEquals("Food & Drink/Barbecues and Grilling", found.path());
        assertEquals("Food & Drink", found.tier1Name());
        assertEquals("Barbecues and Grilling", found.tier2Name());
        assertEquals("", found.tier3Name());
        assertEquals("", found.tier4Name());
        assertEquals("Barbecues and Grilling", found.name());
        assertEquals("", found.extension());

        Term newTaxonomy = new Term(
            found.id(),
            found.parent(),
            found.name(),
            found.tier1Name(),
            found.tier2Name(),
            found.tier3Name(),
            found.tier4Name(),
            Stream
                .of(found.tier1Name(), found.tier2Name(), found.tier3Name(), found.tier4Name())
                .filter(tier -> tier != null && !tier.isEmpty())
                .collect(Collectors.joining("/")),
            found.extension()
        );

        assertEquals(found, newTaxonomy);
    }
}
