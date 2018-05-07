package edu.asu.zoophy.rest.index;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import edu.asu.zoophy.rest.genbank.Location;
import edu.asu.zoophy.rest.security.SecurityHelper;


/**
 * Responsible for retrieving information from Lucene for hierarchy
 * @author kbhangal
 */
@Repository("LuceneHierarchySearcher")
public class LuceneHierarchySearcher {
	private Directory indexDirectory;
	private final static Logger log = Logger.getLogger("LuceneHierarchySearcher");
	
	public LuceneHierarchySearcher(@Value("${lucene.geonames.hierarchy.index.location}") String indexLocation) throws LuceneSearcherException  {	
		try {
			Path index = Paths.get(indexLocation);
			indexDirectory = FSDirectory.open(index);
			log.info("Connected to Index at: "+indexLocation);
		}
		catch (IOException ioe) {
			log.log(Level.SEVERE, "Could not open Lucene Index at: "+indexLocation+ " : "+ioe.getMessage());
			throw new LuceneSearcherException("Could not open Lucene Index at: "+indexLocation+ " : "+ioe.getMessage());
		}
	}
	
	/**
	 * Search ancestors of a location in Lucene
	 * @param geonameIds - valid Lucene query string
	 * @return set of ancestors
	 * @throws LuceneSearcherException
	 */
	public Set<Long> findLocationAncestors(String geonameId) throws LuceneSearcherException{
		Set<Long> ancestors = new HashSet<Long>();
		IndexReader reader = null;
		IndexSearcher indexSearcher = null;
		Query query;
		QueryParser queryParser = null;
		TopDocs documents;
		try {
			reader = DirectoryReader.open(indexDirectory);
			indexSearcher = new IndexSearcher(reader);
			queryParser = new QueryParser("GeonameId", new KeywordAnalyzer());;
			
			query = queryParser.parse("\""+geonameId+"\"");
			log.info("Searching ancestor for : " + query);
			
			documents = indexSearcher.search(query, 1);
			
			if (documents.scoreDocs != null && documents.scoreDocs.length == 1) {
				Document document = indexSearcher.doc(documents.scoreDocs[0].doc);
				for (IndexableField field : document.getFields("AncestorId")) {
					String[] strArray = field.stringValue().split(",");
					for(int i=0; i<strArray.length;i++) {
						ancestors.add(Long.parseLong(strArray[i]));
					}
					log.info("Results : " + ancestors.size());
				}
			}else {
				log.info(" No results");
			}
			return ancestors;
			
		} catch (Exception e) {
			throw new LuceneSearcherException(e.getMessage());
		}finally {
			try {
				if (reader != null) {
					reader.close();
				}
			}
			catch (IOException ioe) {
				log.warning("Could not close IndexReader: "+ioe.getMessage()); 
			}
		}
	}
	
	/**
	 * Search possible Location using geonameID or location name
	 * @param geonameIds - valid Lucene query string
	 * @return map containing Location of each entry
	 * @throws LuceneSearcherException
	 */
	public Map<String, Location> findGeonameLocation(Set<String> completeLocations) throws LuceneSearcherException{
		String location ="",parents ="",queryString="";
		Map<String, Location> records = new HashMap<String, Location>();
		
		IndexReader reader = null;
		IndexSearcher indexSearcher = null;
		Query query;
		QueryParser queryParser = new QueryParser("AncestorName", new KeywordAnalyzer());
		TopDocs documents;
		SortField field = new SortField("Population", SortField.Type.LONG, true);
		Sort sort = new Sort(field);
		
		try {
			reader = DirectoryReader.open(indexDirectory);
			indexSearcher = new IndexSearcher(reader);
			
			for(String completeLocation: completeLocations) {	
				Pattern geoIdRegex = Pattern.compile(SecurityHelper.FASTA_MET_GEOID_REGEX);
				Matcher geoIdMatcher = geoIdRegex.matcher(completeLocation);
				if(geoIdMatcher.matches()){
					queryParser = new QueryParser("GeonameId", new KeywordAnalyzer());
					query = queryParser.parse("\""+completeLocation+"\"");
				} else {
					queryParser = new QueryParser("AncestorName", new StandardAnalyzer());
					String[] Locations = completeLocation.split(",",2);
					
					if(Locations.length>1) {
						location = Locations[0];
						parents = Locations[1];
						parents = parents.replace(",", " ");
						queryString = "AncestorName:"+parents + " AND Name:\""+location+"\"";
					}else {
						location = completeLocation;
						queryString = "Name:"+location +" OR Country:"+location;
					}
					query = queryParser.parse(queryString);
				}
				documents = indexSearcher.search(query, 1, sort);
				for (ScoreDoc scoreDoc : documents.scoreDocs) {
					Document document = indexSearcher.doc(scoreDoc.doc);
					records.put(completeLocation, GeonamesDocumentMapper.mapRecord(document));
				}	
			}
		}
		catch (Exception e) {
			throw new LuceneSearcherException(e.getMessage());
		}
		return records;
	}
	
	/**
	 * Tests connection to Lucene Index
	 * @throws LuceneSearcherException
	 */
	@PostConstruct
	private void testIndex() throws LuceneSearcherException {
		log.info("testing ");
		String testLocationAncestor = "4831725";
		
		Set<String> testFindGeonameLocation = new HashSet<String>();
		testFindGeonameLocation.add("Phoenix, Arizona");
		testFindGeonameLocation.add("5317058");
		testFindGeonameLocation.add("8506558");
		
		try {
			Set<Long> testList = findLocationAncestors(testLocationAncestor);
			if(testList.size()!=5) {
				throw new LuceneSearcherException("Test query should have retrieved 5 records, instead retrieved: "+testList.size());
			}
			Map<String, Location> testMap = findGeonameLocation(testFindGeonameLocation);
			if(testMap.size()!=3) {
				throw new LuceneSearcherException("Test query should have retrieved 3 records, instead retrieved: "+testMap.size());
			}
			log.info("Successfully Tested Lucene Connection.");
		}catch(LuceneSearcherException lse) {
			log.log(Level.SEVERE, "Failed to connect to Lucene hierarchyIndex: "+lse.getMessage());
			throw lse;
		}catch (Exception e) {
			log.log(Level.SEVERE, "Failed to connect to Lucene hierarchyIndex: "+e.getMessage());
			throw new LuceneSearcherException("Failed to connect to Lucene hierarchyIndex: "+e.getMessage());
		}
	}
	
	/**
	 * Closes Lucene resources
	 */
	@PreDestroy
	private void close() {
		try {
			indexDirectory.close();
			log.info("Lucene Index closed");
		}
		catch (IOException ioe) {
			log.warning("Issue closing Lucene Index: "+ioe.getMessage());
		}
	}

}
