package edu.asu.zoophy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import edu.asu.zoophy.database.DaoException;
import edu.asu.zoophy.database.GenBankRecordNotFoundException;
import edu.asu.zoophy.database.ZoophyDAO;
import edu.asu.zoophy.genbank.GenBankRecord;
import edu.asu.zoophy.genbank.Location;
import edu.asu.zoophy.index.InvalidLuceneQueryException;
import edu.asu.zoophy.index.LuceneSearcher;
import edu.asu.zoophy.index.LuceneSearcherException;
import edu.asu.zoophy.pipeline.PipelineException;
import edu.asu.zoophy.pipeline.PipelineManager;
import edu.asu.zoophy.pipeline.ZooPhyRunner;
import edu.asu.zoophy.pipeline.utils.DownloadFormat;
import edu.asu.zoophy.pipeline.utils.DownloadFormatter;
import edu.asu.zoophy.pipeline.utils.FormatterException;
import edu.asu.zoophy.security.Parameter;
import edu.asu.zoophy.security.ParameterException;
import edu.asu.zoophy.security.SecurityHelper;

/**
 * Responsible for mapping ZooPhy service requests
 * @author devdemetri
 */
@RestController
public class ZooPhyController {
	
	@Autowired
	private ZoophyDAO dao;
	
	@Autowired
	private LuceneSearcher indexSearcher;
	
	@Autowired
	private SecurityHelper security;
	
	@Autowired
	private PipelineManager manager;
	
	@Value("${job.max.accessions}")
	private Integer JOB_MAX_ACCESSIONS;
	
	@Autowired
	private DownloadFormatter formatter;
	
	private static Logger log = Logger.getLogger("ZooPhyController");
	
    /**
     * Retrieves the specified record from the database.
     * @param accession - Accession of GenBankRecord to be retrieved
     * @param isFull - does the record need its associated Publication and Genes
     * @return specified record from the database.
     * @throws GenBankRecordNotFoundException
     * @throws DaoException 
     * @throws ParameterException
     */
    @RequestMapping(value="/record", method=RequestMethod.GET)
    @ResponseStatus(value=HttpStatus.OK)
    public GenBankRecord getDatabaseRecord(@RequestParam(value="accession") String accession, @RequestParam(value="isfull", required=false, defaultValue="true") Boolean isFull) throws GenBankRecordNotFoundException, DaoException, ParameterException {
    	if (security.checkParameter(accession, Parameter.ACCESSION)) {
	    	GenBankRecord record = null;
	    	if (isFull) {
	    		log.info("Retrieving full record: "+accession);
	    		record = dao.retrieveFullRecord(accession);
	    	}
	    	else {
	    		log.info("Retrieving light record: "+accession);
	    		record = dao.retrieveLightRecord(accession);
	    	}
	    	log.info("Successfully retrieved record: "+accession);
	    	return record;
    	}
    	else {
    		log.warning("Bad record accession parameter: "+accession);
    		throw new ParameterException(accession);
    	}
    }
    
    /**
     * Returns the Geoname Location for the given Accession
     * @param accession
     * @return Location of the accession
     * @throws DaoException 
     * @throws GenBankRecordNotFoundException 
     * @throws ParameterException 
     */
    @RequestMapping(value="/location", method=RequestMethod.GET)
    @ResponseStatus(value=HttpStatus.OK)
    public Location getRecordLocation(@RequestParam(value="accession") String accession) throws GenBankRecordNotFoundException, DaoException, ParameterException {
    	if (security.checkParameter(accession, Parameter.ACCESSION)) {
    		log.info("Retrieving record location: "+accession);
    		Location location = dao.retrieveLocation(accession);
    		log.info("Successfully retrieved location: "+accession);
    		return location;
    	}
    	else {
    		log.warning("Bad accession parameter: "+accession);
    		throw new ParameterException(accession);
    	}
    }
    
    /**
     * Retrieve GenBankRecords for resulting Lucene query
     * @param query - Valid Lucene query string
     * @return GenBankRecord results of given query.
     * @throws LuceneSearcherException 
     * @throws InvalidLuceneQueryException 
     * @throws ParameterException 
     */
    @RequestMapping(value="/search", method=RequestMethod.GET)
    @ResponseStatus(value=HttpStatus.OK)
    public List<GenBankRecord> queryLucene(@RequestParam(value="query") String query) throws LuceneSearcherException, InvalidLuceneQueryException, ParameterException {
    	if (security.checkParameter(query, Parameter.LUCENE_QUERY)) {
    		log.info("Searching query: "+query);
    		List<GenBankRecord> results = indexSearcher.searchIndex(query);
    		log.info("Successfully searched query: "+query);
    		return results;
    	}
    	else {
    		log.warning("Bad query parameter: "+query);
    		throw new ParameterException(query);
    	}
    }
    
    /**
     * As a results of HTTP Header size conflicts, this service is meant to query a long specific list of Accession.
     * @param accessions - Accessions to query
     * @return list of index GenBankRecords for the given accessions
     * @throws ParameterException
     * @throws LuceneSearcherException
     * @throws InvalidLuceneQueryException
     */
    @RequestMapping(value="/search/accessions", method=RequestMethod.POST)
    @ResponseStatus(value=HttpStatus.OK)
    public List<GenBankRecord> queryAccessions(@RequestBody List<String> accessions) throws ParameterException, LuceneSearcherException, InvalidLuceneQueryException {
    	List<GenBankRecord> records = null;
    	log.info("Searching accession list...");
    	if (accessions != null && accessions.size() > 0) {
    		if (accessions.size() > 1023) {
    			log.warning("Accession list is too long.");
    			throw new ParameterException("accessions list is too long");
    		}
    		Set<String> uniqueAccessions = new LinkedHashSet<String>(accessions.size());
    		for (String accession : accessions) {
    			if (security.checkParameter(accession, Parameter.ACCESSION)) {
    				uniqueAccessions.add(accession);
    			}
    			else {
    				log.warning("Bad accession parameter: "+accession);
    				throw new ParameterException(accession);
    			}
    		}
    		List<String> usableAccessions = new LinkedList<String>(uniqueAccessions);
    		uniqueAccessions.clear();
    		StringBuilder queryBuilder = new StringBuilder("Accession: (");
    		queryBuilder.append(usableAccessions.get(0));
    		usableAccessions.remove(0);
    		for (String accession : usableAccessions) {
    			queryBuilder.append(" OR ");
    			queryBuilder.append(accession);
    		}
    		usableAccessions.clear();
    		queryBuilder.append(")");
    		// TODO: Need to use a different method to allow over 1024 accessions down the road. 
    		records = indexSearcher.searchIndex(queryBuilder.toString());
    		log.info("Successfully searched accession list.");
    	}
    	else {
    		log.warning("Accession list is empty.");
    		records = new LinkedList<GenBankRecord>();
    	}
    	return records;
    }
    
    /**
     * @param replyEmail - User email for results
     * @param jobName - Custom job name (optional)
     * @param accessions - List of accessions to to run the job on
     * @return JobID for the started ZooPhy job
     * @throws ParameterException
     */
    @RequestMapping(value="/run", method=RequestMethod.POST, headers="Accept=application/json")
    @ResponseStatus(value=HttpStatus.ACCEPTED)
    public String runZooPhyJob(@RequestBody JobParameters parameters) throws ParameterException, PipelineException {
    	log.info("Starting new job...");
    	if (security.checkParameter(parameters.getReplyEmail(), Parameter.EMAIL)) {
    		ZooPhyRunner zoophy;
	    	if (parameters.getJobName() == null) {
	    			zoophy = new ZooPhyRunner(parameters.getReplyEmail(), null);
	    	}
	    	else {
	    		if (security.checkParameter(parameters.getJobName(), Parameter.JOB_NAME)) {
	    			zoophy = new ZooPhyRunner(parameters.getReplyEmail(), parameters.getJobName());
	    		}
	    		else {
	    			log.warning("Bad job name parameter: "+parameters.getJobName());
	    			throw new ParameterException(parameters.getJobName());
	    		}
	    	}
	    	Set<String> jobAccessions = new LinkedHashSet<String>(parameters.getAccessions().size());
	    	for(String accession : parameters.getAccessions()) {
	    		if  (security.checkParameter(accession, Parameter.ACCESSION)) {
	    			jobAccessions.add(accession);
	    		}
	    		else {
	    			log.warning("Bad accession parameter: "+accession);
	    			throw new ParameterException(accession);
	    		}
	    	}
	    	if (jobAccessions.size() > JOB_MAX_ACCESSIONS) {
	    		log.warning("Job accession list is too long.");
	    		throw new ParameterException("accessions list is too long");
	    	}
	    	manager.startZooPhyPipeline(zoophy, new ArrayList<String>(jobAccessions));
	    	log.info("Job successfully started: "+zoophy.getJobID());
	    	return zoophy.getJobID();
    	}
    	else {
    		log.warning("Bad reply email parameter: "+parameters.getReplyEmail());
    		throw new ParameterException(parameters.getReplyEmail());
    	}
    }
    
    /**
     * Stop a running ZooPhyJob by the Job ID
     * @param jobID - ID of Job to be stopped
     * @throws PipelineException
     * @throws ParameterException
     */
    @RequestMapping(value="/stop", method=RequestMethod.GET)
    @ResponseStatus(value=HttpStatus.OK)
    public String stopZooPhyJob(@RequestParam(value="id") String jobID) throws PipelineException, ParameterException {
    	if (security.checkParameter(jobID, Parameter.JOB_ID)) {
    		log.info("Stopping ZooPhy Job: "+jobID);
    		try {
	    		manager.killJob(jobID);
	    		log.info("Successfully stopped ZooPhy Job: "+jobID);
	    		return "Successfully stopped ZooPhy Job: "+jobID;
    		}
    		catch (PipelineException pe) {
    			log.warning("Failed to stop job: "+jobID+" : "+pe.getMessage());
    			throw new ParameterException(jobID);
    		}
    	}
    	else {
    		log.warning("Bad Job ID parameter: "+jobID);
    		throw new ParameterException(jobID);
    	}
    }
    
    /**
     * Generate the contents of GenBankRecords download in the specified format
     * @param format 
     * @param accessions
     * @return String of GenBankRecords download in the specified format
     * @throws ParameterException
     * @throws FormatterException
     */
    @RequestMapping(value="/download", method=RequestMethod.POST)
    @ResponseStatus(value=HttpStatus.OK)
    public String retrieveDownload(@RequestParam(value="format") String format, @RequestBody List<String> accessions) throws ParameterException, FormatterException {
    	log.info("Setting up download...");
    	if (format != null) {
    		if (accessions == null || accessions.size() == 0) {
    			log.warning("Empty accession list.");
    			return null;
    		}
    		if (accessions.size() > 2500) {
    			log.warning("Too many accessions.");
    			throw new ParameterException("accessions list is too long");
    		}
    		Set<String> downloadAccessions = new LinkedHashSet<String>(accessions.size());
    		for (String accession : accessions) {
    			if  (security.checkParameter(accession, Parameter.ACCESSION)) {
    				downloadAccessions.add(accession);
	    		}
	    		else {
	    			log.warning("Bad accession parameter: "+accession);
	    			throw new ParameterException(accession);
	    		} 
    		}
    		accessions = new LinkedList<String>(downloadAccessions);
    		downloadAccessions.clear();
    		String download = null;
    		if (format.equalsIgnoreCase("CSV")) {
    			log.info("Generating CSV download...");
    			download = formatter.generateDownload(accessions, DownloadFormat.CSV);
    		}
    		else if (format.equalsIgnoreCase("FASTA")) {
    			log.info("Generating FASTA download...");
    			download = formatter.generateDownload(accessions, DownloadFormat.FASTA);
    		}
    		else {
    			log.warning("Bad format parameter: "+format);
    			throw new ParameterException(format);
    		}
    		log.info("Successfully generated download.");
    		return download;
    	}
    	else {
    		log.warning("Bad format parameter: "+format);
    		throw new ParameterException(format);
    	}
    }
    
}
