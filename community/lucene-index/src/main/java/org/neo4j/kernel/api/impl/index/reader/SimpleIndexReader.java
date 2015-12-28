/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.index.reader;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FilteredDocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.helpers.TaskControl;
import org.neo4j.helpers.TaskCoordinator;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.impl.index.DocValuesCollector;
import org.neo4j.kernel.api.impl.index.LuceneDocumentRetrievalException;
import org.neo4j.kernel.api.impl.index.LuceneDocumentStructure;
import org.neo4j.kernel.api.impl.index.partition.PartitionSearcher;
import org.neo4j.kernel.api.impl.index.reader.sample.LuceneIndexSampler;
import org.neo4j.kernel.api.impl.index.reader.sample.NonUniqueLuceneIndexSampler;
import org.neo4j.kernel.api.impl.index.reader.sample.UniqueLuceneIndexSampler;
import org.neo4j.kernel.api.index.IndexConfiguration;
import org.neo4j.kernel.api.index.PropertyAccessor;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.register.Register;
import org.neo4j.storageengine.api.schema.IndexReader;

import static org.neo4j.kernel.api.impl.index.LuceneDocumentStructure.NODE_ID_KEY;

public class SimpleIndexReader implements IndexReader
{
    private PartitionSearcher partitionSearcher;
    private IndexConfiguration indexConfiguration;
    private final IndexSamplingConfig samplingConfig;
    private TaskCoordinator taskCoordinator;
    private LuceneDocumentStructure documentLogic = new LuceneDocumentStructure();

    public SimpleIndexReader( PartitionSearcher partitionSearcher,
            IndexConfiguration indexConfiguration,
            IndexSamplingConfig samplingConfig,
            TaskCoordinator taskCoordinator )
    {
        this.partitionSearcher = partitionSearcher;
        this.indexConfiguration = indexConfiguration;
        this.samplingConfig = samplingConfig;
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public long sampleIndex( Register.DoubleLong.Out result ) throws IndexNotFoundKernelException
    {
        LuceneIndexSampler sampler = createIndexSampler();
        return sampler.sampleIndex( result );
    }

    private LuceneIndexSampler createIndexSampler()
    {
        TaskControl taskControl = taskCoordinator.newInstance();
        if ( indexConfiguration.isUnique() )
        {
            return new UniqueLuceneIndexSampler( getIndexSearcher(), taskControl );
        }
        else
        {
            return new NonUniqueLuceneIndexSampler( getIndexSearcher(), taskControl, samplingConfig );
        }
    }

    @Override
    public void verifyDeferredConstraints( Object accessor, int propertyKeyId )
            throws Exception
    {
        try
        {
            PropertyAccessor propertyAccessor = (PropertyAccessor) accessor;
            DuplicateCheckingCollector collector = new DuplicateCheckingCollector( propertyAccessor, documentLogic,
                    propertyKeyId );
            IndexSearcher searcher = partitionSearcher.getIndexSearcher();
            for ( LeafReaderContext leafReaderContext : searcher.getIndexReader().leaves() )
            {
                Fields fields = leafReaderContext.reader().fields();
                for ( String field : fields )
                {
                    if ( NODE_ID_KEY.equals( field ) )
                    {
                        continue;
                    }

                    TermsEnum terms = fields.terms( field ).iterator();
                    BytesRef termsRef;
                    while ( (termsRef = terms.next()) != null )
                    {
                        if ( terms.docFreq() > 1 )
                        {
                            collector.reset();
                            searcher.search( new TermQuery( new Term( field, termsRef ) ), collector );
                        }
                    }
                }
            }
        }
        catch ( IOException e )
        {
            Throwable cause = e.getCause();
            if ( cause instanceof IndexEntryConflictException )
            {
                throw (IndexEntryConflictException) cause;
            }
            throw e;
        }
    }

    @Override
    public void verifyDeferredConstraints( Object accessor, int propertyKeyId,
            List<Object> updatedPropertyValues ) throws Exception
    {
        try
        {
            PropertyAccessor propertyAccessor = (PropertyAccessor) accessor;
            DuplicateCheckingCollector collector = new DuplicateCheckingCollector( propertyAccessor, documentLogic,
                    propertyKeyId );
            for ( Object propertyValue : updatedPropertyValues )
            {
                collector.reset();
                Query query = documentLogic.newSeekQuery( propertyValue );
                getIndexSearcher().search( query, collector );
            }
        }
        catch ( IOException e )
        {
            Throwable cause = e.getCause();
            if ( cause instanceof IndexEntryConflictException )
            {
                throw (IndexEntryConflictException) cause;
            }
            throw e;
        }
    }

    @Override
    public long getMaxDoc()
    {
        return getIndexSearcher().getIndexReader().maxDoc();
    }

    @Override
    public Iterator<Document> getAllDocsIterator()
    {
        return new PrefetchingIterator<Document>()
        {
            private DocIdSetIterator idIterator = iterateAllDocs();

            @Override
            protected Document fetchNextOrNull()
            {
                try
                {
                    int doc = idIterator.nextDoc();
                    if ( doc == DocIdSetIterator.NO_MORE_DOCS )
                    {
                        return null;
                    }
                    return getDocument( doc );
                }
                catch ( IOException e )
                {
                    throw new LuceneDocumentRetrievalException( "Can't fetch document id from lucene index.", e );
                }
            }
        };
    }

    private Document getDocument( int docId )
    {
        try
        {
            return getIndexSearcher().doc( docId );
        }
        catch ( IOException e )
        {
            throw new LuceneDocumentRetrievalException("Can't retrieve document with id: " + docId + ".", docId, e );
        }
    }

    private DocIdSetIterator iterateAllDocs()
    {
        org.apache.lucene.index.IndexReader reader = getIndexSearcher().getIndexReader();
        final Bits liveDocs = MultiFields.getLiveDocs( reader );
        final DocIdSetIterator allDocs = DocIdSetIterator.all( reader.maxDoc() );
        if ( liveDocs == null )
        {
            return allDocs;
        }

        return new FilteredDocIdSetIterator( allDocs )
        {
            @Override
            protected boolean match( int doc )
            {
                return liveDocs.get( doc );
            }
        };
    }

    @Override
    public PrimitiveLongIterator seek( Object value )
    {
        return query( documentLogic.newSeekQuery( value ) );
    }

    @Override
    public PrimitiveLongIterator rangeSeekByNumberInclusive( Number lower, Number upper )
    {
        return query( documentLogic.newInclusiveNumericRangeSeekQuery( lower, upper ) );
    }

    @Override
    public PrimitiveLongIterator rangeSeekByString( String lower, boolean includeLower,
            String upper, boolean includeUpper )
    {
        return query( documentLogic.newRangeSeekByStringQuery( lower, includeLower, upper, includeUpper ) );
    }

    @Override
    public PrimitiveLongIterator rangeSeekByPrefix( String prefix )
    {
        return query( documentLogic.newRangeSeekByPrefixQuery( prefix ) );
    }

    @Override
    public PrimitiveLongIterator scan()
    {
        return query( documentLogic.newScanQuery() );
    }

    @Override
    public int countIndexedNodes( long nodeId, Object propertyValue )
    {
        Query nodeIdQuery = new TermQuery( documentLogic.newTermForChangeOrRemove( nodeId ) );
        Query valueQuery = documentLogic.newSeekQuery( propertyValue );
        BooleanQuery.Builder nodeIdAndValueQuery = new BooleanQuery.Builder().setDisableCoord( true );
        nodeIdAndValueQuery.add( nodeIdQuery, BooleanClause.Occur.MUST );
        nodeIdAndValueQuery.add( valueQuery, BooleanClause.Occur.MUST );
        try
        {
            TotalHitCountCollector collector = new TotalHitCountCollector();
            getIndexSearcher().search( nodeIdAndValueQuery.build(), collector );
            // A <label,propertyKeyId,nodeId> tuple should only match at most a single propertyValue
            return collector.getTotalHits();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public void close()
    {
        try
        {
            partitionSearcher.close();
        }
        catch ( IOException e )
        {
            throw new IndexReaderCloseException( e );
        }
    }

    protected PrimitiveLongIterator query( Query query )
    {
        try
        {
            DocValuesCollector docValuesCollector = new DocValuesCollector();
            getIndexSearcher().search( query, docValuesCollector );
            return docValuesCollector.getValuesIterator( NODE_ID_KEY );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    private IndexSearcher getIndexSearcher()
    {
        return partitionSearcher.getIndexSearcher();
    }
}
