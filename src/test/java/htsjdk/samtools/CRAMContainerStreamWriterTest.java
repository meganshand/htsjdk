package htsjdk.samtools;

import htsjdk.HtsjdkTest;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.reference.InMemoryReferenceSequenceFile;
import htsjdk.samtools.util.CloseableIterator;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CRAMContainerStreamWriterTest extends HtsjdkTest {

    final static int SEQUENCE_LENGTH = 1024 * 1024;

    private List<SAMRecord> createRecords(int count) {
        final List<SAMRecord> list = new ArrayList<>(count);
        final SAMRecordSetBuilder builder = new SAMRecordSetBuilder();
        if (builder.getHeader().getReadGroups().isEmpty()) {
            throw new IllegalStateException("Read group expected in the header");
        }

        int posInRef = 1;
        for (int i = 0; i < count / 2; i++) {
            builder.addPair(Integer.toString(i), i % 2, posInRef += 1, posInRef += 3);
        }
        list.addAll(builder.getRecords());

        Collections.sort(list, new SAMRecordCoordinateComparator());

        return list;
    }

    private SAMFileHeader createSAMHeader(SAMFileHeader.SortOrder sortOrder) {
        final SAMFileHeader header = new SAMFileHeader();
        header.setSortOrder(sortOrder);
        header.addSequence(new SAMSequenceRecord("chr1", SEQUENCE_LENGTH));
        header.addSequence(new SAMSequenceRecord("chr2", SEQUENCE_LENGTH));
        SAMReadGroupRecord readGroupRecord = new SAMReadGroupRecord("1");
        header.addReadGroup(readGroupRecord);
        return header;
    }

    private ReferenceSource createReferenceSource() {
        final byte[] refBases = new byte[SEQUENCE_LENGTH];
        Arrays.fill(refBases, (byte) 'A');
        InMemoryReferenceSequenceFile rsf = new InMemoryReferenceSequenceFile();
        rsf.add("chr1", refBases);
        rsf.add("chr2", refBases);
        return new ReferenceSource(rsf);
    }

    private void doTest(final List<SAMRecord> samRecords, final ByteArrayOutputStream outStream, final OutputStream indexStream) {
        final SAMFileHeader header = createSAMHeader(SAMFileHeader.SortOrder.coordinate);
        final ReferenceSource refSource = createReferenceSource();

        final CRAMContainerStreamWriter containerStream = new CRAMContainerStreamWriter(outStream, indexStream, refSource, header, "test");
        containerStream.writeHeader();

        writeThenReadRecords(samRecords, outStream, refSource, containerStream);
    }

    private void doTestWithIndexer(final List<SAMRecord> samRecords, final ByteArrayOutputStream outStream, final SAMFileHeader header, final CRAMIndexer indexer) {
        final ReferenceSource refSource = createReferenceSource();

        final CRAMContainerStreamWriter containerStream = new CRAMContainerStreamWriter(outStream, refSource, header, "test", indexer);
        containerStream.writeHeader();

        writeThenReadRecords(samRecords, outStream, refSource, containerStream);
    }

    private void writeThenReadRecords(List<SAMRecord> samRecords, ByteArrayOutputStream outStream, ReferenceSource refSource, CRAMContainerStreamWriter containerStream) {
        for (SAMRecord record : samRecords) {
            containerStream.writeAlignment(record);
        }
        containerStream.finish(true); // finish and issue EOF

        // read all the records back in
        final CRAMFileReader cReader = new CRAMFileReader(null, new ByteArrayInputStream(outStream.toByteArray()), refSource);
        final SAMRecordIterator iterator = cReader.getIterator();
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        Assert.assertEquals(count, samRecords.size());
    }

    @Test(description = "Test CRAMContainerStream no index")
    public void testCRAMContainerStreamNoIndex() {
        final List<SAMRecord> samRecords = createRecords(100);
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        doTest(samRecords, outStream, null);
    }

    @Test(description = "Test CRAMContainerStream aggregating multiple partitions")
    public void testCRAMContainerAggregatePartitions() throws IOException {
        final SAMFileHeader header = createSAMHeader(SAMFileHeader.SortOrder.coordinate);
        final ReferenceSource refSource = createReferenceSource();

        // create a bunch of records and write them out to separate streams in groups
        final int nRecs = 100;
        final int recsPerPartition = 20;
        final int nPartitions = nRecs/recsPerPartition;

        final List<SAMRecord> samRecords = createRecords(nRecs);
        final ArrayList<ByteArrayOutputStream> byteStreamArray = new ArrayList<>(nPartitions);

        for (int partition = 0, recNum = 0; partition < nPartitions; partition++) {
            byteStreamArray.add(partition, new ByteArrayOutputStream());
            final CRAMContainerStreamWriter containerStream =
                    new CRAMContainerStreamWriter(byteStreamArray.get(partition), null, refSource, header, "test");

            // don't write a header for the intermediate streams
            for (int i = 0; i <  recsPerPartition; i++) {
                containerStream.writeAlignment(samRecords.get(recNum++));
            }
            containerStream.finish(false); // finish but don't issue EOF container
        }

        // now create the final aggregate file by concatenating the individual streams, but this
        // time with a CRAM and SAM header at the front and an EOF container at the end
        final ByteArrayOutputStream aggregateStream = new ByteArrayOutputStream();
        final CRAMContainerStreamWriter aggregateContainerStreamWriter = new CRAMContainerStreamWriter(aggregateStream, null, refSource, header, "test");
        aggregateContainerStreamWriter.writeHeader(); // write out one CRAM and SAM header
        for (int j = 0; j < nPartitions; j++) {
            byteStreamArray.get(j).writeTo(aggregateStream);
        }
        aggregateContainerStreamWriter.finish(true);// write out the EOF container

        // now iterate through all the records in the aggregate file
        final CRAMFileReader cReader = new CRAMFileReader(null, new ByteArrayInputStream(aggregateStream.toByteArray()), refSource);
        final SAMRecordIterator iterator = cReader.getIterator();
        int count = 0;
        while (iterator.hasNext()) {
            Assert.assertEquals(iterator.next().toString(), samRecords.get(count).toString());
            count++;
        }
        Assert.assertEquals(count, nRecs);
    }

    @Test(description = "Test CRAMContainerStream with bai index")
    public void testCRAMContainerStreamWithBaiIndex() throws IOException {
        final List<SAMRecord> samRecords = createRecords(100);
        try (ByteArrayOutputStream outStream = new ByteArrayOutputStream();
             ByteArrayOutputStream indexStream = new ByteArrayOutputStream()) {
            doTest(samRecords, outStream, indexStream);
            outStream.flush();
            indexStream.flush();
            checkCRAMContainerStream(outStream, indexStream, ".bai");
        }
    }

    @Test(description = "Test CRAMContainerStream with crai index")
    public void testCRAMContainerStreamWithCraiIndex() throws IOException {
        final List<SAMRecord> samRecords = createRecords(100);
        final SAMFileHeader header = createSAMHeader(SAMFileHeader.SortOrder.coordinate);
        try (ByteArrayOutputStream outStream = new ByteArrayOutputStream();
             ByteArrayOutputStream indexStream = new ByteArrayOutputStream()) {
            doTestWithIndexer(samRecords, outStream, header, new CRAMCRAIIndexer(indexStream, header));
            outStream.flush();
            indexStream.flush();
            checkCRAMContainerStream(outStream, indexStream, ".crai");
        }
    }

    private void checkCRAMContainerStream(ByteArrayOutputStream outStream, ByteArrayOutputStream indexStream, String indexExtension) throws IOException {
        // write the file out
        final File cramTempFile = File.createTempFile("cramContainerStreamTest", ".cram");
        cramTempFile.deleteOnExit();
        final OutputStream cramFileStream = new FileOutputStream(cramTempFile);
        cramFileStream.write(outStream.toByteArray());
        cramFileStream.close();

        // write the index out
        final File indexTempFile = File.createTempFile("cramContainerStreamTest", indexExtension);
        indexTempFile.deleteOnExit();
        OutputStream indexFileStream = new FileOutputStream(indexTempFile);
        indexFileStream.write(indexStream.toByteArray());
        indexFileStream.close();

        final ReferenceSource refSource = createReferenceSource();
        final CRAMFileReader reader = new CRAMFileReader(
                cramTempFile,
                indexTempFile,
                refSource,
                ValidationStringency.SILENT);
        final CloseableIterator<SAMRecord> iterator = reader.query(new QueryInterval[]{new QueryInterval(1, 10, 10)}, false);
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        Assert.assertEquals(count, 2);
    }

}
