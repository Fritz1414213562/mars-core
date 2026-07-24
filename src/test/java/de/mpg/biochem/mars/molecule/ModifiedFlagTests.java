/*-
 * #%L
 * Molecule Archive Suite (Mars) - core data storage and processing algorithms.
 * %%
 * Copyright (C) 2018 - 2026 Karl Duderstadt
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package de.mpg.biochem.mars.molecule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.mpg.biochem.mars.metadata.MarsOMEMetadata;
import de.mpg.biochem.mars.util.MarsMath;
import de.mpg.biochem.mars.util.MarsPosition;
import de.mpg.biochem.mars.util.MarsRegion;

/**
 * Tests for the transient {@code modified} dirty flag on {@link MarsRecord}s:
 * new records start dirty, mutators dirty them, {@code put}/{@code putMetadata}
 * clean them, and records just loaded from disk are clean.
 */
public class ModifiedFlagTests {

	@TempDir
	File tempDir;

	@Test
	void newMoleculeIsModified() {
		SingleMolecule molecule = new SingleMolecule(MarsMath.getUUID58());
		assertTrue(molecule.isModified());
	}

	@Test
	void putClearsModified() {
		SingleMoleculeArchive archive = new SingleMoleculeArchive("test");
		SingleMolecule molecule = new SingleMolecule(MarsMath.getUUID58());
		molecule.setTable(MoleculeArchiveTests.generateRandomTable(5));

		archive.put(molecule);

		assertFalse(molecule.isModified());
	}

	@Test
	void mutatorsSetModifiedAfterPut() {
		SingleMoleculeArchive archive = new SingleMoleculeArchive("test");
		SingleMolecule molecule = new SingleMolecule(MarsMath.getUUID58());
		molecule.setTable(MoleculeArchiveTests.generateRandomTable(5));
		archive.put(molecule);
		assertFalse(molecule.isModified());

		molecule.setParameter("slope", 1.5);
		assertTrue(molecule.isModified());
		archive.put(molecule);
		assertFalse(molecule.isModified());

		molecule.addTag("below30");
		assertTrue(molecule.isModified());
		archive.put(molecule);
		assertFalse(molecule.isModified());

		molecule.putRegion(new MarsRegion("region1", "T", 10, 20, "#42A5F5", 0.2));
		assertTrue(molecule.isModified());
		archive.put(molecule);
		assertFalse(molecule.isModified());

		molecule.putPosition(new MarsPosition("position1", "T", 15, "#42A5F5",
			2.0));
		assertTrue(molecule.isModified());
		archive.put(molecule);
		assertFalse(molecule.isModified());

		molecule.putSegmentsTable("x", "y", MoleculeArchiveTests
			.generateRandomTable(3));
		assertTrue(molecule.isModified());
		archive.put(molecule);
		assertFalse(molecule.isModified());
	}

	@Test
	void loadedVirtualRecordIsNotModified() throws IOException {
		SingleMoleculeArchive archive = MoleculeArchiveTests
			.generateSingleMoleculeArchive();

		File storeDir = new File(tempDir, "modifiedFlagTestArchive.yama.store");
		archive.saveAsVirtualStore(storeDir);

		SingleMoleculeArchive reloaded = new SingleMoleculeArchive(storeDir);

		String UID = reloaded.getMoleculeUIDs().get(0);
		SingleMolecule molecule = reloaded.get(UID);

		assertFalse(molecule.isModified());
	}

	@Test
	void deserializedRecordIsNotModified() throws IOException {
		SingleMolecule molecule = new SingleMolecule(MarsMath.getUUID58());
		molecule.setTable(MoleculeArchiveTests.generateRandomTable(5));
		molecule.setParameter("slope", 1.5);
		molecule.addTag("dirty");
		assertTrue(molecule.isModified());

		JsonFactory jFactory = new JsonFactory();

		StringWriter writer = new StringWriter();
		JsonGenerator jGenerator = jFactory.createGenerator(writer);
		molecule.toJSON(jGenerator);
		jGenerator.close();

		JsonParser jParser = jFactory.createParser(writer.toString());
		SingleMolecule reloaded = new SingleMolecule(jParser);
		jParser.close();

		assertFalse(reloaded.isModified());
	}

	@Test
	void putMetadataClearsModified() {
		SingleMoleculeArchive archive = new SingleMoleculeArchive("test");
		MarsOMEMetadata metadata = MoleculeArchiveTests.generateMetadata(1, 1, 5);
		assertTrue(metadata.isModified());

		archive.putMetadata(metadata);
		assertFalse(metadata.isModified());

		metadata.setParameter("someParameter", 42.0);
		assertTrue(metadata.isModified());
	}
}
