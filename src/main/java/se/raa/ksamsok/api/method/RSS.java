package se.raa.ksamsok.api.method;

import com.rometools.modules.georss.GeoRSSModule;
import com.rometools.modules.georss.W3CGeoModuleImpl;
import com.rometools.modules.georss.geometries.Position;
import com.rometools.modules.mediarss.MediaEntryModule;
import com.rometools.modules.mediarss.MediaEntryModuleImpl;
import com.rometools.modules.mediarss.types.MediaContent;
import com.rometools.modules.mediarss.types.Metadata;
import com.rometools.modules.mediarss.types.Thumbnail;
import com.rometools.modules.mediarss.types.UrlReference;
import com.rometools.rome.feed.module.Module;
import com.rometools.rome.feed.module.ModuleImpl;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Selector;
import org.apache.jena.rdf.model.SimpleSelector;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RiotException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.z3950.zing.cql.CQLNode;
import org.z3950.zing.cql.CQLParseException;
import org.z3950.zing.cql.CQLParser;
import se.raa.ksamsok.api.APIServiceProvider;
import se.raa.ksamsok.api.exception.BadParameterException;
import se.raa.ksamsok.api.exception.DiagnosticException;
import se.raa.ksamsok.api.util.StaticMethods;
import se.raa.ksamsok.api.util.parser.CQL2Solr;
import se.raa.ksamsok.lucene.ContentHelper;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;

/**
 * Metod f??r att f?? tillbaka en mediaRSS feed p?? ett s??kresultat
 * fr??n K-sams??k
 * @author Henrik Hjalmarsson
 */
public class RSS extends AbstractSearchMethod {
	/** metodens namn */
	public static final String METHOD_NAME = "rss";
	/** standard v??rde f??r antal tr??ffar per sida */
	public static final int DEFAULT_HITS_PER_PAGE = 100;
	
	// rss version
	private static final String RSS_2_0 = "rss_2.0";
	private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", new Locale("sv",	"SE"));
	private static final Logger logger = LogManager.getLogger(RSS.class);
	
	//fabriker
	private static final DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
	
	//URIs f??r att navigera RDF
	private static final String URI_PREFIX = "http://kulturarvsdata.se/";
	private static final String URI_PREFIX_KSAMSOK = URI_PREFIX + "ksamsok#";
	private static final URI URI_RDF_TYPE = URI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
	private static final URI URI_KSAMSOK_ENTITY = URI.create(URI_PREFIX_KSAMSOK + "Entity");
	private static final URI URI_PRESENTATION = URI.create(URI_PREFIX_KSAMSOK + "presentation");
	private static final URI URI_ITEM_TITLE = URI.create(URI_PREFIX_KSAMSOK + "itemTitle");
	private static final URI URI_ITEM_KEY_WORD = URI.create(URI_PREFIX_KSAMSOK + "itemKeyWord");
	private static final URI URI_BUILD_DATE = URI.create(URI_PREFIX_KSAMSOK + "buildDate");

	/**
	 * Skapar ett objekt av RSS
	 * @param queryString CQL query str??ng f??r att s??ka mot indexet
	 * @param hitsPerPage hur m??nga tr??ffar som skall visas per sida
	 * @param startRecord vart i resultatet s??kningen skall starta
	 * @param out anv??nds f??r att skriva svaret
	 * @throws DiagnosticException TODO
	 */
	public RSS(APIServiceProvider serviceProvider, OutputStream out, Map<String,String> params) throws DiagnosticException {
		super(serviceProvider, out, params);
	}

	@Override
	protected int getDefaultHitsPerPage() {
		return DEFAULT_HITS_PER_PAGE;
	}

	@Override
	protected void performMethodLogic() throws DiagnosticException {
		try {
			SolrQuery q = createQuery();
			q.setRows(hitsPerPage);
			// start ??r 0-baserad
			q.setStart(startRecord - 1);
			// f??lt att h??mta
			q.addField(ContentHelper.IX_ITEMID);
			q.addField(ContentHelper.I_IX_RDF);
			QueryResponse qr = serviceProvider.getSearchService().query(q);
			hitList = qr.getResults();
		} catch (SolrServerException | IOException e) {
			throw new DiagnosticException("Ov??ntat IO-fel", "RSS.doSearch", e.getMessage(), true);
		} catch (BadParameterException e) {
			throw new DiagnosticException("Ov??ntat parserfel uppstod", "RSS.doSearch", e.getMessage(), true);
		}

	}

	@Override
	protected void writeResult() throws DiagnosticException {
		SyndFeed feed = getFeed();
		
		feed.setEntries(getEntries(hitList));
		SyndFeedOutput output = new SyndFeedOutput();
		PrintWriter w = new PrintWriter(out);
		try {
			output.output(feed, w);
		} catch (FeedException e) {
			logger.error(e);
			throw new DiagnosticException("Det ??r problem med att generera ett RSS-fl??de", this.getClass().getName(), e.getMessage(), false);
		} catch (IOException e) {
			logger.error(e);
			throw new DiagnosticException("Det ??r problem med att skriva resultatet till utstr??mmen", this.getClass().getName(), e.getMessage(), false);
		} catch (Throwable t) {
			logger.error(t);
			throw new DiagnosticException("Ov??ntat problem med att generera ett RSS-fl??de: ", this.getClass().getName(), t.getMessage(), false);
		}
	}

	/**
	 * Skapar query
	 * @return Ett Lucene query
	 * @throws DiagnosticException
	 * @throws BadParameterException
	 */
	private SolrQuery createQuery() throws DiagnosticException, BadParameterException {
		SolrQuery q = new SolrQuery();
		try {
			CQLParser parser = new CQLParser();
			CQLNode rootNode = parser.parse(queryString);
			String qs = CQL2Solr.makeQuery(rootNode);
			q.setQuery(qs);
		} catch (CQLParseException e) {
			throw new DiagnosticException("Parser fel. Kontrollera query str??ng", "RSS.createQuery", null, false);
		} catch (IOException e) {
			throw new DiagnosticException("Ov??ntat IO fel. F??rs??k igen", "RSS.createQuery", e.getMessage(), true);
		}
		return q;
	}
	
	/**
	 * returnerar en lista med RSS feed entries
	 * @param numberOfDocs 
	 * @param nDocs
	 * @param searcher
	 * @param hits
	 * @param entries
	 * @return
	 * @throws DiagnosticException 
	 */
	protected List<SyndEntry> getEntries(SolrDocumentList hits)
		throws DiagnosticException {
		List<SyndEntry> entries = new Vector<>();
		try {
			for (SolrDocument d: hits) {
				String uri = (String) d.getFieldValue(ContentHelper.IX_ITEMID);
				String content = null;
				byte[] xmlData = (byte[]) d.getFieldValue(ContentHelper.I_IX_RDF);
				if (xmlData != null) {
					content = new String(xmlData, StandardCharsets.UTF_8);
				}
				if (content != null) {
					try {
						entries.add(getEntry(content));
					} catch (RiotException rio) {
						logger.warn("Ogiltigt rdf-data f??r " + uri);
					}
				} else {
					logger.warn("Hittade inte rdf-data f??r " + uri);
				}
			}
		} catch(Exception e) {
			throw new DiagnosticException("Fel vid h??mtning av data", "RSS.getEntries", e.getMessage(), true);
		}
		return entries;
	}
	
	/**
	 * skapar ett entry till RSS feed
	 * @param content XML data som en str??ng
	 * @return ett entry med data fr??n XML str??ng
	 * @throws DiagnosticException 
	 */
	protected SyndEntry getEntry(String content) throws DiagnosticException {
		SyndEntry entry = new SyndEntryImpl();
		try {
			RssObject data = getData(content);
			entry.setTitle(data.getTitle());
			entry.setLink(data.getLink());
			entry.setUri(data.getIdentifier());
			
			SyndContent syndContent = new SyndContentImpl();
			syndContent.setType("text/plain");
			syndContent.setValue(data.getDescription());
			entry.setDescription(syndContent);
			String thumb = data.getThumbnailUrl();
			String image = data.getImageUrl();
			if (data.getCoords() != null) {
				GeoRSSModule geoRssModule = getGeoRssModule(data.getCoords());
				if (geoRssModule != null) {
					entry.getModules().add(geoRssModule);
				}
			}
			if (!StringUtils.isEmpty(thumb) && !StringUtils.isEmpty(image)) {
				entry.getModules().add(getMediaModule(data, thumb, image));
			}
			synchronized (sdf) {
				if(data.getPublishDate() != null) {
					entry.setPublishedDate(sdf.parse(data.getPublishDate()));
				}
			}
		} catch (ParseException ignore) {}
		return entry;
	}
	
	private GeoRSSModule getGeoRssModule(String coords)
	{
		GeoRSSModule m = new W3CGeoModuleImpl();
		try {
			String[] coordsSplit = coords.split(",");
			double lon = Double.parseDouble(StringUtils.trim(coordsSplit[0]));
			double lat = Double.parseDouble(StringUtils.trim(coordsSplit[1]));
			m.setPosition(new Position(lat, lon));
		}catch(Exception e) {
			e.printStackTrace();
			return null;
		}
		return m;
	}
	
	/**
	 * H??mtar data fr??n RDF dokument
	 * @param content textstr??ng inneh??llande RDF data
	 * @return ett Rssobjekt med data
	 * @throws DiagnosticException - om fel uppst??r vid h??mtning av data 
	 */
	private RssObject getData(String content) 
		throws DiagnosticException
	{
		RssObject data = new RssObject();
		Model model;
		model = getModel(content);
		Property rRdfType = ResourceFactory.createProperty(URI_RDF_TYPE.toString());
		RDFNode rKsamsokEntity = ResourceFactory.createResource(URI_KSAMSOK_ENTITY.toString());
		Property rPresentation = ResourceFactory.createProperty(URI_PRESENTATION.toString());
		Property rItemTitle = ResourceFactory.createProperty(URI_ITEM_TITLE.toString());//elementFactory.createURIReference(URI_ITEM_TITLE);
		Property rItemKeyWord = ResourceFactory.createProperty(URI_ITEM_KEY_WORD.toString());
		Property rBuildDate = ResourceFactory.createProperty(URI_BUILD_DATE.toString());
		Resource subject = getSubjectNode(model, rRdfType, rKsamsokEntity);
		data.setIdentifier(subject.toString());
		data.setTitle(getValueFromGraph(model, subject, rItemTitle, null));
		data = getDataFromPresentationBlock(getSingleValueFromGraph(model, subject, rPresentation), data);

		String itemKeyWordsString = getValueFromGraph(model, subject, rItemKeyWord, null);
		String[] itemKeyWords = new String[0];
		if(itemKeyWordsString != null) {
			itemKeyWords = itemKeyWordsString.split(" ");
		}
		for (String itemKeyWord : itemKeyWords) {
			data.addKeyWord(itemKeyWord);
		}
		data.setPublishDate(getSingleValueFromGraph(model, subject, rBuildDate));
		return data;
	}
	
	/**
	 * Skapar en RDF graf fr??n textstr??ng
	 * @param content RDF data som textstr??ng
	 * @return RDF graf
	 */
	private Model getModel(String content) {
		Model m;
		try (StringReader reader = new StringReader(content)) {
			m = ModelFactory.createDefaultModel();
			m.read(reader, "");
		}
		return m;
	}
	
	/**
	 * returnerar root subject noden
	 * @param model - grafen som noden skall h??mtas ur
	 * @param rRdfType - URI referens till rdfType
	 * @param rKsamsokEntity - URI referens till ksamsokEntity
	 * @return en subject node
	 * @throws DiagnosticException om n??got fel uppst??r n??r subject noden skall h??mtas ur grafen
	 */
	//TODO! Ska rKsamsokEntity vara URIReference?
	private Resource getSubjectNode(Model model, Property rRdfType, RDFNode rKsamsokEntity) 
		throws DiagnosticException
	{
		Selector selector = new SimpleSelector(null, rRdfType, rKsamsokEntity);
		StmtIterator iter = model.listStatements(selector);
		Resource subject = null;
		while (iter.hasNext()){
			if (subject != null) {
				throw new DiagnosticException("Ska bara finnas en entity i rdf-grafen", "se.raa.ksamsok.api.method.RSS.getSubjectNode", null, true);
			}
			subject = iter.next().getSubject();
		}
		if (subject == null) {
			logger.error("Hittade ingen entity i rdf-grafen:\n" + model);
			throw new DiagnosticException("Hittade ingen entity i rdf-grafen", "se.raa.ksamsok.api.method.RSS.getSubjectNode", null, true);
		}
		return subject;
	}
	
	/**
	 * h??mtar data fr??n presentationsblocket
	 * @param presentationBlock presentationsblocket som textstr??ng
	 * @param data Rss objektet som datan skall l??ggas i
	 * @return RssObject med data
	 */
	private RssObject getDataFromPresentationBlock(String presentationBlock, RssObject data)
		throws DiagnosticException
	{
		org.w3c.dom.Document doc = getDOMDocument(presentationBlock); 
		NodeList nodeList = doc.getElementsByTagName("pres:item").item(0).getChildNodes();
		for(int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			switch (node.getNodeName()) {
				case "pres:description":
					if (data.getDescription() != null) {
						data.setDescription(data.getDescription() + " " + node.getTextContent());
					} else {
						data.setDescription(node.getTextContent());
					}
					break;
				case "pres:representations": {
					NodeList childNodes = node.getChildNodes();
					for (int j = 0; j < childNodes.getLength(); j++) {
						Node child = childNodes.item(j);
						if (child.getAttributes().getNamedItem("format").getTextContent().equals("HTML")) {
							data.setLink(child.getTextContent());
						}
					}
					break;
				}
				case "pres:image": {
					NodeList childNodes = node.getChildNodes();
					for (int j = 0; j < childNodes.getLength(); j++) {
						Node child = childNodes.item(j);
						if (child.getNodeName().equals("pres:src")) {
							if (child.getAttributes().getNamedItem("type").getTextContent().equals("lowres")) {
								data.setImageUrl(child.getTextContent());
							} else if (child.getAttributes().getNamedItem("type").getTextContent().equals("thumbnail")) {
								data.setThumbnailUrl(child.getTextContent());
							}
						}
					}
					break;
				}
				case "pres:itemLabel":
					if (StringUtils.trimToNull(data.getTitle()) == null) {
						data.setTitle(node.getTextContent());
					}
					break;
				case "georss:where":
					Node child = node.getFirstChild().getFirstChild();
					data.setCoords(StringUtils.trimToNull(child.getTextContent()));
					break;
			}
		}
		return data;
	}
	
	/**
	 * Skapar ett DOM document f??r presentationsblocket
	 * @param presentationBlock - presentationsblocket som textstr??ng
	 * @return
	 * @throws DiagnosticException
	 */
	private org.w3c.dom.Document getDOMDocument(String presentationBlock)
		throws DiagnosticException
	{
		StringReader reader = null;
		org.w3c.dom.Document doc;
		try {
			reader = new StringReader(presentationBlock);
			DocumentBuilder builder = domFactory.newDocumentBuilder();
			doc = builder.parse(new InputSource(reader));
		} catch (ParserConfigurationException | IOException | SAXException e) {
			throw new DiagnosticException("Internt fel", "RSS.getDOMDocument", e.getMessage(), true);
		} finally {
			if(reader != null) {
				reader.close();
			}
		}
		return doc;
	}
	
	/**
	 * H??mtar ett v??rde fr??n RDF graf
	 * @param m - grafen som v??rdet skall h??mtas ur
	 * @param sn - subject nod
	 * @param pn - predicate nod
	 * @return v??rde fr??n graf som textstr??ng
	 */
	private String getSingleValueFromGraph(Model m, Resource sn, Property pn) {
		Selector selector = new SimpleSelector(sn, pn, (RDFNode) null);
		StmtIterator iter = m.listStatements(selector);
		String value = null;
		while (iter.hasNext()){
			Statement s = iter.next();
			if (s.getObject().isLiteral()){
				value = StringUtils.trimToNull(s.getObject().asLiteral().getString()) + " ";
			} else if (s.getObject().isURIResource()) {
				value = StringUtils.trimToNull(s.getObject().asResource().getURI());
			}
		}
		return value;
	}
	
	/**
	 * H??mtar ett eller flera v??rden fr??n given RDF graf
	 * @param model - RDF graf
	 * @param subject - subject nod
	 * @param ref - URI referens till 
	 * @param refRef - URI referens till eventuella subnoder
	 * @return v??rden som textstr??ng
	 */
	private String getValueFromGraph(Model model, Resource subject, Property ref, Property refRef) {
		final String sep = " ";
		StringBuilder buf = new StringBuilder();
		String value;
		Selector selector = new SimpleSelector(subject, ref, (RDFNode) null);
		StmtIterator iter = model.listStatements(selector);
		while (iter.hasNext()){
			Statement s = iter.next();
			if (s.getObject().isLiteral()){
				Literal l = s.getObject().asLiteral();
				if (buf.length() > 0) {
					buf.append(sep);
				}
				value = l.getString();
				buf.append(value);
			} else if (s.getObject().isURIResource()){
				Resource r =s.getObject().asResource();
				value = StringUtils.trimToNull(r.getURI());
				// l??gg till i buffer bara om detta ??r en uri vi ska sl?? upp v??rde f??r
				if (value != null) {
					if (buf.length() > 0) {
						buf.append(sep);
					}
					buf.append(value);
				}
			} else if (s.getObject().isResource()){
				Resource r = s.getObject().asResource();
				value = getSingleValueFromGraph(model, r, refRef);
				if (value != null) {
					if (buf.length() > 0) {
						buf.append(sep);
					}
					buf.append(value);
				}
			}
		}
		return buf.length() > 0 ? StringUtils.trimToNull(buf.toString()) : null;
	}
	
	/**
	 * Skapar en RSS feed och s??tter n??gra av dess attribut.
	 * @return SyndFeed
	 */
	protected SyndFeed getFeed()
	{
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(RSS_2_0);
		feed.setTitle("K-sams??k s??kresultat");
		feed.setLink(getFeedLinkProperty());
		feed.setDescription("S??kresultat av en s??kning mot K-sams??k API");
		
		
		return feed;
	}
	
	/**
	 * Skapar och s??tter v??rden f??r en media module om bilder finns
	 * @param data rss-objekt
	 * @param thumbnailUrl
	 * @param imageUrl
	 * @return Mediamodule med tumnagel och bild
	 * @throws DiagnosticException 
	 */
	protected MediaEntryModule getMediaModule(RssObject data, String thumbnailUrl, String imageUrl) 
		throws DiagnosticException
	{
		String thumb = StaticMethods.encode(thumbnailUrl);
		String image = StaticMethods.encode(imageUrl);
		MediaEntryModuleImpl mediaModule = new MediaEntryModuleImpl();
		try 
		{	
			mediaModule.setMetadata(getMetadata(data, thumb, mediaModule));
			mediaModule.setMediaContents(getImage(image));
		} catch (URISyntaxException e)
		{
			throw new DiagnosticException("Ov??ntat fel uppstod", "se.raa.ksamsok.api.method.RSS.getMediaModule()", e.getMessage(), true);
		}
		return mediaModule;
	}
	
	/**
	 * Skapar en bild i form av ett MediaContent[]
	 * @param image bildens URL
	 * @return MediaContent[] inneh??llande bild data
	 * @throws URISyntaxException
	 */
	protected MediaContent[] getImage(String image) 
		throws URISyntaxException
	{
		MediaContent[] contents = new MediaContent[1];
		MediaContent mediaContent = 
			new MediaContent(new UrlReference(image));
		mediaContent.setType(getImageType(image));
		contents[0] = mediaContent;		
		return contents;
	}
	
	/**
	 * returnerar ett Metadata objekt med URL till en tumnagel bild
	 * @param data rss-objekt
	 * @param thumb URL till tumnagel
	 * @param mediaModule MediaModule som anv??nds
	 * @return Metadata objekt med tumnagel
	 * @throws DiagnosticException
	 */
	protected Metadata getMetadata(RssObject data, String thumb, MediaEntryModule mediaModule) 
		throws DiagnosticException
	{
		Metadata metadata = mediaModule.getMetadata();
		if (metadata == null)
		{
			metadata = new Metadata();
		}
		metadata.setKeywords(data.getKeywordsAsArray());
		metadata.setThumbnail(getThumbnail(thumb));
		return metadata;
	}
	
	private Thumbnail[] getThumbnail(String thumb) 
		throws DiagnosticException
	{
		Thumbnail thumbnail;
		try {
			thumbnail = new Thumbnail(new URI(thumb));
		} catch (URISyntaxException e) {
			throw new DiagnosticException("N??nting blev fel", "RSS.getThumbnail", e.getMessage(), true);
		}
		Thumbnail[] thumbnails = new Thumbnail[1];
		thumbnails[0] = thumbnail;
		return thumbnails;
	}
	
	/**
	 * returnerar en st??ng med MIME f??r bild
	 * @param image bild som skall f?? en MIME
	 * @return MIME som str??ng
	 */
	protected String getImageType(String image)
	{
		String imageType = "image/jpeg"; // default
		if (image.endsWith(".gif")) 
		{
			imageType = "image/gif";						
		} else if (image.endsWith(".png")) 
		{
			imageType = "image/png";						
		}
		return imageType;
	}
	
	/**
	 * Returnerar en str??ng med den URL som anv??nts f??r att f?? detta 
	 * resultat
	 * @return URL som str??ng
	 */
	protected String getFeedLinkProperty()
	{
        return "http://www.kulturarvsdata.se";
	}
	
	/**
	 * B??na (typ) som h??ller data om en RSS entitet
	 * @author Henrik Hjalmarsson
	 */
	public static class RssObject
	{
		private String identifier;
		private String title;
		private String link;
		private String description;
		private String thumbnailUrl;
		private String imageUrl;
		private List<String> keyWords;
		private String publishDate;
		private String coords;
		
		public RssObject()
		{
			keyWords = new Vector<>();
		}
		
		/**
		 * returnerar listan med nyckelord som en array
		 * @return
		 */
		public String[] getKeywordsAsArray()
		{
			String[] result = new String[keyWords.size()];
			for(int i = 0; i < keyWords.size(); i++) {
				result[i] = keyWords.get(i);
			}
			return result;
		}

		/**
		 * L??gger till ett nyckelord till nyckelordslistan
		 * @param keyWord
		 */
		public void addKeyWord(String keyWord)
		{
			keyWords.add(keyWord);
		}

		public String getPublishDate()
		{
			return publishDate;
		}

		public void setPublishDate(String publishDate)
		{
			this.publishDate = publishDate;
		}

		public void setThumbnailUrl(String url)
		{
			thumbnailUrl = url;
		}
		
		public String getThumbnailUrl()
		{
			return thumbnailUrl;
		}
		
		public void setImageUrl(String url)
		{
			imageUrl = url;
		}
		
		public String getImageUrl()
		{
			return imageUrl;
		}
		
		public void setTitle(String title)
		{
			this.title = title;
		}
		
		public String getTitle()
		{
			return title;
		}
		
		public void setLink(String link)
		{
			this.link = link;
		}
		
		public String getLink()
		{
			return link;
		}
		
		public void setDescription(String description)
		{
			this.description = description;
		}
		
		public String getDescription()
		{
			return description;
		}

		public void setCoords(String coords)
		{
			this.coords = coords;
		}

		public String getCoords()
		{
			return coords;
		}
		public String getIdentifier() {
			return identifier;
		}
		public void setIdentifier(String identifier) {
			this.identifier = identifier;
		}
	}

	@Override
	protected void generateDocument() {
		// H??r g??r vi inget
		
	}
}
