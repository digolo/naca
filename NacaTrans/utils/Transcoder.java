/*
 * NacaTrans - Naca Transcoder v1.2.0.
 *
 * Copyright (c) 2008-2009 Publicitas SA.
 * Licensed under GPL (GPL-LICENSE.txt) license.
 */
/*
 * NacaRTTests - Naca Tests for NacaRT support.
 *
 * Copyright (c) 2005, 2006, 2007, 2008 Publicitas SA.
 * Licensed under GPL (GPL-LICENSE.txt) license.
 */
package utils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;
import java.util.Vector;

import jlib.misc.AsciiEbcdicConverter;
import jlib.misc.NumberParser;
import jlib.misc.StringUtil;
import jlib.sql.SQLTypeOperation;
import jlib.xml.Tag;
import jlib.xml.TagCursor;
import lexer.CTokenList;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import parser.Cobol.elements.SQL.CExecSQLDeclareTable;
import parser.Cobol.elements.SQL.CSQLTableColDescriptor;

import utils.DCLGenConverter.DCLGenConverter;
import utils.SQLSyntaxConverter.SQLSyntaxConverter;
import utils.modificationsReporter.Reporter;

/**
 * @author S. Charton
 * @version $Id$
 */
public class Transcoder
{
	private static Logger ms_logger;

	private CRulesManager m_RulesManager = CRulesManager.getInstance() ;
	
	private String m_csInfoDir = "" ;
	
	private CGlobalEntityCounter m_EntityCounter ;
	private Hashtable<String, CTransApplicationGroup> m_tabGroups = new Hashtable<String, CTransApplicationGroup>();
	private Vector<CTransApplicationGroup> m_arrGroups = new Vector<CTransApplicationGroup>() ;
	private Tag m_eConf = null ;
	private Hashtable<String, BaseEngine> m_tabEngines = null ;
	private TranscoderAction m_transcoderAction = TranscoderAction.All;
	//private DCLGenConverter m_DCLGenConverter = null;
	private static DCLGenConverter ms_DCLGenConverter = null;
	private static SQLSyntaxConverter ms_SQLSyntaxConverter = null; 
	
	private static int ms_nReportLineCounter = 0;	

	private static boolean ms_bGenerateCheckNumberIndexes = true;
	private static UnboundRefIdColl ms_arrUnboundRef = null;
	private static int ms_nNbWarning = 0;
	private static int ms_nNbError = 0;
	private static int ms_nLastLine = 0;
	
	private static boolean m_bSQLCheck = false;
	private static Connection m_connection = null;
	private static int ms_nSentenceIndex = -1;
	private static boolean ms_bNextSentenceToLocate = false;
	private static boolean ms_bRecreateCobolSourceFile = false;	// Set to true to create the cobol source file
	
	public static String getVersion()
	{
		return "1.2.11";
	}
	
	public boolean Init(Tag eConf)
	{
		PathsManager.Load(eConf);
		
		Tag tagPaths = eConf.getChild("Paths") ;
		
		m_eConf = eConf ;
		String log4jConf = eConf.getVal("Log4jConf");
		log4jConf = PathsManager.adjustPath(log4jConf);
		
		File f = new File(log4jConf) ;
		if (f.isFile())
		{
			PropertyConfigurator.configure(log4jConf);
		}
		ms_logger = Logger.getLogger("LogFile");
		ms_logger.setLevel(Level.INFO);
		logInfo("Starting NacaTrans Version " + getVersion());
		
		logInfo("Init rules...");
		InitGlobals(eConf) ;
		
		logInfo("Loading DCLGEN converter settings ...");
		LoadDCLGENConverter(eConf) ;
		
		logInfo("Init Engines...");
		if (!LoadEngines())
		{
			return false ;
		}

		
		logInfo("Loading paths...");
		LoadGroups(eConf) ;
		
		logInfo("Init global objects...");
		m_EntityCounter = CGlobalEntityCounter.GetInstance() ;
		
		LoadApplications() ;
		
		return true ;
	}
	
	public void LoadSQLSyntaxConverter(Tag eConf)
	{
		logInfo("Loading SQLSyntax converter settings ...");
		Tag tagEngines = eConf.getChild("Engines");
		if(tagEngines == null)
			return ;
		
		Tag tag = tagEngines.getChild("SQLSyntaxConverter");
		if(tag == null)
			return ;
		
		ms_SQLSyntaxConverter = new SQLSyntaxConverter();
		boolean b = ms_SQLSyntaxConverter.fill(tag);
		if(b)
		{
			ms_SQLSyntaxConverter.doLexing(this);
		}
	}

	/**
	 * 
	 */
	private boolean LoadEngines()
	{
		Tag tagEngines = m_eConf.getChild("Engines") ;
		if (tagEngines != null)
		{
			m_tabEngines = new Hashtable<String, BaseEngine>() ;
			TagCursor cur = new TagCursor() ;
			Tag tagTrans = tagEngines.getFirstChild(cur, "Transcoder") ;
			while (tagTrans != null)
			{
				String name = tagTrans.getVal("Name") ;
				String cl = tagTrans.getVal("Class") ;
				try
				{
					BaseEngine engine = (BaseEngine)Class.forName(cl).newInstance() ;
					engine.setRulesManager(m_RulesManager);
					engine.setDCLGenConverter(ms_DCLGenConverter);
					engine.setSQLSyntaxConverter(ms_SQLSyntaxConverter);
					
					engine.setTranscoder(this) ;
					if (!engine.MainInit(tagTrans))
					{
						ms_logger.error("Failure while Engine init : "+name);
						return false ;
					}
					m_tabEngines.put(name, engine) ;
				}
				catch (InstantiationException e)
				{
					e.printStackTrace();
					return false ;
				}
				catch (IllegalAccessException e)
				{
					e.printStackTrace();
					return false ;
				}
				catch (ClassNotFoundException e)
				{
					e.printStackTrace();
					return false ;
				}
				tagTrans = m_eConf.getNextChild(cur) ;
			}
		}
		return true ;
	}
	private void InitGlobals(Tag conf)
	{
		Tag ePaths = conf.getChild("GlobalPaths");
		if (ePaths != null)
		{
			String path = ePaths.getVal("RuleFilePath") ;
			path = PathsManager.adjustPath(path);
			
			if (path != null && !path.equals(""))
			{
				m_RulesManager.LoadRulesFile(path);
			}
			
			m_csInfoDir = ePaths.getVal("InfoPath") ;
			m_csInfoDir = PathsManager.adjustPath(m_csInfoDir);
		}
	}


	private void LoadGroups(Tag eConf)
	{
		Tag eGroups = eConf.getChild("Groups") ;
		if (eGroups != null)
		{
			TagCursor cur = new TagCursor() ;
			Tag eGroup = eGroups.getFirstChild(cur, "Group") ;
			while (eGroup != null)
			{
				String csOutputDir = eGroup.getVal("OutputPath") ;
				csOutputDir = PathsManager.adjustPath(csOutputDir);
				
				String csName = eGroup.getVal("Name") ;
				String engineName = eGroup.getVal("Engine") ;
				
				
				// Special case for Pub2000GenResJava groups (Transcoder of .res and .java from a .xml file)
				String csOutputDir2 = null; 
				if(eGroup.isValExisting("OutputPath2"))
					csOutputDir2 = eGroup.getVal("OutputPath2") ;
				BaseEngine engine = m_tabEngines.get(engineName) ;
//				if (engine != null)
//				{
					CTransApplicationGroup grp = new CTransApplicationGroup(engine) ;
					grp.m_csName = csName ;
					grp.m_csOutputPath = csOutputDir ;
					new File(csOutputDir).mkdirs() ;
					grp.m_csInputPath = eGroup.getVal("InputPath") ;
					grp.m_csInputPath = PathsManager.adjustPath(grp.m_csInputPath);
					
					grp.m_csInterPath = eGroup.getVal("InterPath") ;
					grp.m_csInterPath = PathsManager.adjustPath(grp.m_csInterPath);
					
					new File(grp.m_csInterPath).mkdirs() ;
					String csType = eGroup.getVal("Type") ;
					if (csType.equalsIgnoreCase("Online"))
					{
						grp.m_eType = CTransApplicationGroup.EProgramType.TYPE_ONLINE ;
					}
					else if (csType.equalsIgnoreCase("Batch"))
					{
						grp.m_eType = CTransApplicationGroup.EProgramType.TYPE_BATCH ;
					}
					else if (csType.equalsIgnoreCase("Included"))
					{
						grp.m_eType = CTransApplicationGroup.EProgramType.TYPE_INCLUDED ;
					}
					else if (csType.equalsIgnoreCase("Map"))
					{
						grp.m_eType = CTransApplicationGroup.EProgramType.TYPE_MAP;
					}
					else
					{
						grp.m_eType = CTransApplicationGroup.EProgramType.TYPE_CALLED;
					}
					m_tabGroups.put(csName, grp) ;
					m_arrGroups.add(grp) ;
//				}
				eGroup = eGroups.getNextChild(cur) ;
			}
		}
	}
	
	private void LoadApplications()
	{
		TagCursor curGrp = new TagCursor() ;
		Tag eGroup = m_eConf.getFirstChild(curGrp, "Group") ;
		while (eGroup != null)
		{
			String grpName = eGroup.getVal("Name")  ;
			CTransApplicationGroup group = m_tabGroups.get(grpName) ;
			if (group != null)
			{
				TagCursor cur = new TagCursor() ;
				Tag eApp = eGroup.getFirstChild(cur, "Application") ;
				while (eApp != null)
				{
					String name = eApp.getVal("Name") ;
					group.m_tabApplication.put(name, eApp) ;
					group.m_arrApplications.addElement(name) ;
					eApp = eGroup.getNextChild(cur) ;
				}
			}
			eGroup = m_eConf.getNextChild(curGrp) ;
		}
	}

	public int getNbGroups()
	{
		return m_arrGroups.size() ;
	}
	public String getGroupName(int i)
	{
		if (i<m_arrGroups.size())
		{
			return m_arrGroups.get(i).m_csName ;
		}
		return "" ;
	}
	public int getNbApplications(String group)
	{
		CTransApplicationGroup grp = m_tabGroups.get(group) ;
		if (grp != null)
		{
			return grp.m_arrApplications.size() ;
		}
		return 0 ;
	}
	public String getApplicationName(String group, int i)
	{
		CTransApplicationGroup grp = m_tabGroups.get(group) ;
		if (grp != null)
		{
			String cs = grp.m_arrApplications.get(i) ;
			return cs ;
		}
		return "" ;
	}

	@SuppressWarnings("unchecked")
	public void DoAllApplications()
	{
		for (BaseEngine engine : m_tabEngines.values())
		{
			engine.getGlobalCatalog().ClearFormContainers() ;
		}
		for (int j=0; j<m_arrGroups.size(); j++)
		{
			CTransApplicationGroup grp = m_arrGroups.get(j) ;
			for (int i=0; i<grp.m_arrApplications.size(); i++)
			{
				String app = grp.m_arrApplications.elementAt(i) ;
				Tag tag = grp.m_tabApplication.get(app) ;
				DoApplication(tag, grp) ;
			}
		}
		for (BaseEngine engine : m_tabEngines.values())
		{
			engine.getGlobalCatalog().doRegisteredDependencies() ;
		}
	}

	/**
	 * @param eApp
	 */
	public void DoApplication(Tag eApp, CTransApplicationGroup grp)
	{
		String csCurrentApplication = eApp.getVal("Name") ;
		
		TagCursor cur = new TagCursor() ;
		Tag eFile = eApp.getFirstChild(cur, "File") ;
		while (eFile != null)
		{
			Transcoder.resetNewSentenceIndex();
			
			String fileName = eFile.getVal("Name") ;
			boolean bResources = false;
			if (eFile.isValExisting("Resources"))
				bResources = eFile.getValAsBoolean("Resources");
			
			grp.getEngine().doFileTranscoding(fileName, csCurrentApplication, grp, bResources);
			eFile = eApp.getNextChild(cur) ;
		}
	}

	public void DoApplication(String appName, String groupName)
	{
		CTransApplicationGroup grp = m_tabGroups.get(groupName) ;
		if (grp != null)
		{
			Tag eApp = grp.m_tabApplication.get(appName) ;
			if (eApp == null)
			{
				return ;
			}
			DoApplication(eApp, grp) ;
		}
	}

	public void setTranscoderAction(TranscoderAction transcoderAction)
	{
		m_transcoderAction = transcoderAction;
	}
	
	public boolean mustGenerate()
	{
		if(m_transcoderAction.isGeneration())
			return true;
		return false;
	}
	
	public void initForPlugin(String configFilePath)
	{
		try
		{
			AsciiEbcdicConverter.create();
			Tag eConf = Tag.createFromFile(configFilePath) ;
			if (eConf == null)
			{
				System.out.println("Failure while loading configuration file : "+configFilePath) ;
				return ;
			}
	
			if (!Init(eConf))
			{
				return ;
			}
			LoadSQLSyntaxConverter(eConf);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			Transcoder.logError(ms_nLastLine, "Transcode ERROR: Catched global exception: "+e.toString() + "; please correct other previous errors before transcoding again");
		}
	}
	

	protected static TranscoderAction getTranscoderAction(String csAction)
	{
		TranscoderAction transcoderAction = TranscoderAction.All;
		if (csAction.equalsIgnoreCase("SyntaxCheck"))
			transcoderAction = TranscoderAction.SyntaxCheck;
		return transcoderAction;
	}
	
	private static BasePluginMarker ms_pluginMarker = null;
	
	public void setPlugin(BasePluginMarker pluginMarker)
	{
		ms_pluginMarker = pluginMarker;
	}
	
	public void startForPlugin(String csSingleFile, String csApplication, String csGroupToTranscode, String csAction, boolean bResources)
	{
		m_bSQLCheck = true;
		Transcoder.clearCurrentTranscodedUnits();
		
		TranscoderAction transcoderAction = getTranscoderAction(csAction);
		setTranscoderAction(transcoderAction);

		try
		{
			DoProgramForPlugin(csGroupToTranscode, csApplication, csSingleFile, bResources);
			
			logInfo("Exporting Infos...");
			CGlobalEntityCounter.GetInstance().Export(m_csInfoDir+"ItemCount");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			Transcoder.logError(ms_nLastLine, "Transcode ERROR: Catched global exception: "+e.toString() + "; please correct other previous errors before transcoding again");
		}

		logInfo("Done; Errors="+ms_nNbError + " Warnings="+ms_nNbWarning);
		ms_nNbError = 0;
		ms_nNbWarning = 0;
		ms_pluginMarker = null;
		releaseConnection();
	}
	
	public void Start(String baseDir, String configFilePath, String groupToTranscode)
	{
		try
		{
			AsciiEbcdicConverter.create();
			Tag eConf = Tag.createFromFile(configFilePath) ;
			if (eConf == null)
			{
				System.out.println("Failure while loading configuration file : "+configFilePath) ;
				return ;
			}
	
			if (!Init(eConf))
			{
				return ;
			}
			LoadSQLSyntaxConverter(eConf);
			
			if (groupToTranscode != null && !groupToTranscode.equals(""))
			{
				logInfo("Do groups : "+groupToTranscode);
				DoApplications(groupToTranscode) ;
			}
			else
			{
				logInfo("Do Applications...");
				DoApplications(null) ;
			}
			
			logInfo("Exporting Infos...");
			CGlobalEntityCounter.GetInstance().Export(m_csInfoDir+"ItemCount");
			if(ms_DCLGenConverter != null)
				ms_DCLGenConverter.close();
			Reporter.export(m_csInfoDir+"ReportModifictions.xml");
		}
		catch (Exception e)
		{
			Transcoder.logError(ms_nLastLine, "Transcode ERROR: Catched global exception: "+e.toString() + "; please correct other previous errors before transcoding again");
			e.printStackTrace();
		}

		logInfo("Done; Errors="+ms_nNbError + " Warnings="+ms_nNbWarning);		
	}

	@SuppressWarnings("unchecked")
	protected void DoApplications(String groupToTranscode)
	{
		if (groupToTranscode != null && !groupToTranscode.equals(""))
		{
			Vector<CGlobalCatalog> arrCatalogs = new Vector<CGlobalCatalog>() ;
			String[] arrGroups = groupToTranscode.split(";") ;
			for (String group : arrGroups)
			{
				CTransApplicationGroup grp = m_tabGroups.get(group) ;
				if (grp != null)
				{
					for (int i=0; i<grp.m_arrApplications.size(); i++)
					{
						String app = grp.m_arrApplications.elementAt(i) ;
						Tag tag = grp.m_tabApplication.get(app) ;
						DoApplication(tag, grp) ;
					}
					if (!arrCatalogs.contains(grp.getEngine().getGlobalCatalog()))
						arrCatalogs.add(grp.getEngine().getGlobalCatalog()) ;
				}
			}
			for (CGlobalCatalog cat : arrCatalogs)
			{
				cat.doRegisteredDependencies() ;
			}
		}
		else
		{
			TagCursor cur = new TagCursor() ;
			Tag eFile = m_eConf.getFirstChild(cur, "SingleFile") ;
			boolean bFound = eFile != null ;
			while (eFile != null)
			{
				String fileName = eFile.getVal("Name") ;
				boolean bResources = false;
				if (eFile.isValExisting("Resources"))
					bResources = eFile.getValAsBoolean("Resources");
				String csCurrentApplication = eFile.getVal("Application") ;
				String csCurrentGroup = eFile.getVal("Group") ;
				CTransApplicationGroup grp = m_tabGroups.get(csCurrentGroup) ;
				if (grp != null && !csCurrentApplication.equals(""))
				{
					Transcoder.pushTranscodedUnit(fileName, grp.m_csInputPath);
					grp.getEngine().doFileTranscoding(fileName, csCurrentApplication, grp, bResources);
					Transcoder.popTranscodedUnit();
				}
				eFile = m_eConf.getNextChild(cur) ;
			}
			if (!bFound)
			{		
				DoAllApplications() ;
			}
		}
	}
	
	public CTokenList doTextTranscoding(String csGroup, String csText, boolean bFromSource)
	{
		CTransApplicationGroup grp = m_tabGroups.get(csGroup) ;
		if(grp != null)
		{
			if(grp.getEngine() != null)
			{
				CTokenList lst = grp.getEngine().doTextTranscoding(csText, bFromSource);
				return lst;
			}
		}
		return null;
	}
	
	public String[] getProgramsForApplication(String group, String appName)
	{
		CTransApplicationGroup grp = m_tabGroups.get(group) ;
		if (grp != null)
		{
			Tag eApp = grp.m_tabApplication.get(appName);
			if (eApp != null)
			{
				TagCursor cur = new TagCursor() ;
				Vector<String> arr = new Vector<String>();
				Tag eFile = eApp.getFirstChild(cur, "File") ;
				while (eFile != null)
				{
					String fileName = eFile.getVal("Name") ;
					arr.add(fileName) ;
					eFile = eApp.getNextChild(cur) ;
				}	
				String[] sa = new String[arr.size()]  ;
				return arr.toArray(sa) ;
			}
		}
		return new String[] {"<none>"};
	}
	/**
	 * @param appName
	 * @param prgName
	 */
	public void DoProgram(String group, String appName, String prgName)
	{
		CTransApplicationGroup grp = m_tabGroups.get(group) ;
		if (grp != null)
		{
			Tag eApp = grp.m_tabApplication.get(appName) ;
			if (eApp == null)
			{
				return ;
			}
			String csCurrentApplication = eApp.getVal("Name") ;
			
			TagCursor cur = new TagCursor() ;
			Tag eFile = eApp.getFirstChild(cur, "File") ;
			while (eFile != null)
			{
				String fileName = eFile.getVal("Name") ;
				if (fileName.equals(prgName))
				{	
					boolean bResources = false;
					if (eFile.isValExisting("Resources"))
						bResources = eFile.getValAsBoolean("Resources");
					grp.getEngine().doFileTranscoding(prgName, csCurrentApplication, grp, bResources);
					break;
				}
				eFile = eApp.getNextChild(cur) ;
			}
		}
	}
	
	/**
	 * @param appName
	 * @param prgName
	 */
	public void DoProgramForPlugin(String group, String appName, String prgName, boolean bResources)
	{
		CTransApplicationGroup grp = m_tabGroups.get(group) ;
		if (grp != null)
		{
			Transcoder.pushTranscodedUnit(prgName, grp.m_csInputPath);
			grp.getEngine().doFileTranscoding(prgName, appName, grp, bResources);
		}
	}
	
	/**
	 * @param csCallGroupName
	 * @return
	 */
	public CTransApplicationGroup getGroup(String csGroupName)
	{
		return m_tabGroups.get(csGroupName);
	}
	
	public static Logger getLogger()
	{
		return ms_logger;
	}
	
	public static void addOnceUnboundReference(int nLine, String csName)
	{
		String csFile = Transcoder.getCurrentTranscodedUnit();
		String csUniqueName = csFile + "/" + csName;
		if(ms_arrUnboundRef == null)
			ms_arrUnboundRef = new UnboundRefIdColl();
		UnboundRefId ref = ms_arrUnboundRef.find(csUniqueName);
		if(ref == null)
			ms_arrUnboundRef.add(nLine, csName, csFile);
		else
			ref.addLineOnce(nLine);
	}
	
	public static int dumpUnboundReferences()
	{
		if(ms_arrUnboundRef != null)
		{
			Enumeration<String> enumNames = ms_arrUnboundRef.getKeys();
			while(enumNames.hasMoreElements())
			{
				String csName = enumNames.nextElement();
				UnboundRefId unboundRefId = ms_arrUnboundRef.getVal(csName);
				if(unboundRefId != null)
				{
					String csLines = unboundRefId.getAllLinesAsString();
					if(!StringUtil.isEmpty(csLines))
						csLines = "; Other lines: " + csLines;
					String csfile = unboundRefId.getFile();
					if(!StringUtil.isEmpty(csfile))
						Transcoder.logError(csfile, unboundRefId.getFirstLine(), "Unbound reference/identity : " + csName + csLines);
				}
			}
		}
		ms_arrUnboundRef = null;
		return 0;
	}
	
	public static void pushTranscodedUnit(String csTranscodedUnit, String csPath)
	{
		String cs = csPath + csTranscodedUnit;
		ms_stackTranscodedUnits.push(cs);
	}
	
	public static void popTranscodedUnit()
	{
		ms_stackTranscodedUnits.pop();
	}
	
	public static String getCurrentTranscodedUnit()
	{
		if(ms_stackTranscodedUnits.size() > 0)
			return ms_stackTranscodedUnits.lastElement();
		return "";
	}
	
	public static String resetCurrentTranscodedUnit()
	{
		if(ms_stackTranscodedUnits.size() > 0)
			return ms_stackTranscodedUnits.lastElement();
		return "";
	}
	
	public static boolean getRecreateCobolSourceFile()
	{
		return ms_bRecreateCobolSourceFile;
	}
	
	public static void setAnalyseExpressionCurrentLine(int nLine)
	{
	}

	public static void resetAnalyseExpressionCurrentLine()
	{
	}

	public static void clearCurrentTranscodedUnits()
	{
		ms_stackTranscodedUnits = new Stack<String>();
	}
	
	private static Stack<String> ms_stackTranscodedUnits = new Stack<String>(); 

	private static int extractLineFromText(String csText)
	{
		String csTextUpper = csText.toUpperCase();
		int nPosStartLine = csTextUpper.indexOf("LINE");
		if(nPosStartLine >= 0)
		{
			while(nPosStartLine < csText.length() && (csTextUpper.charAt(nPosStartLine) < '0' || csTextUpper.charAt(nPosStartLine) > '9'))
				nPosStartLine++;
		}
		if(nPosStartLine >= 0)
		{
			String csRight = csTextUpper.substring(nPosStartLine);
			int nTextRight = csRight.indexOf(" ");
			if(nTextRight >= 0)
			{
				String csNumber = csRight.substring(0, nTextRight);
				int nLine = NumberParser.getAsInt(csNumber);
				return nLine;
			}
		}
		return -1;
	}
	
	private static String makeFullLogText(String csFile, int nLine, String csText, String csCategory)
	{		
		//if(nLine <= 0)
		//	nLine = ms_nLastLine;
		if(nLine > 0)
		{
			String cs = getReportLineCounter() + "["+csCategory + "] %" + csFile + "(" +nLine + ")%- " +csText;
			return cs;
		}
		String cs = getReportLineCounter() + "["+csCategory + "] %" + csFile + "%- " +csText;
		return cs;
	}
	
	public static void logError(String csText)
	{
		String csFile = getCurrentTranscodedUnit();
//		int nLine = extractLineFromText(csText);
//		if(nLine <= 0)
//			nLine = ms_nLastLine;
		String cs = makeFullLogText(csFile, 0, csText, "Error");
		ms_nNbError++;
		showError(cs);
	}
	
	public static void logError(int nLine, String csText)
	{
		String csFile = getCurrentTranscodedUnit();
		if(nLine <= 0)
			nLine = ms_nLastLine;
		String cs = makeFullLogText(csFile, nLine, csText, "Error");
		ms_nNbError++;
		showError(cs);
	}

	public static void logError(String csFile, int nLine, String csText)
	{
		if(nLine <= 0)
			nLine = ms_nLastLine;
		String cs = makeFullLogText(csFile, nLine, csText, "Error");
		ms_nNbError++;
		showError(cs);
	}
	
	public static void logWarn(int nLine, String csText)
	{
		String csFile = getCurrentTranscodedUnit();
		if(nLine <= 0)
			nLine = ms_nLastLine;
		String cs = makeFullLogText(csFile, nLine, csText, "Warning");
		ms_nNbWarning++;
		showWarn(cs);
	}
	
	public static void logInfo(String csText)
	{
		String csFile = getCurrentTranscodedUnit();
		String cs = makeFullLogText(csFile, 0, csText, "Info");
		showInfo(cs);
	}
	
	public static void logInfo(int nLine, String csText)
	{
		String csFile = getCurrentTranscodedUnit();
		if(nLine <= 0)
			nLine = ms_nLastLine;
		String cs = makeFullLogText(csFile, nLine, csText, "Info");
		showInfo(cs);
	}	
	
	private static void showError(String cs)
	{
		if(ms_pluginMarker != null)
			ms_pluginMarker.error(cs);
		ms_logger.error(cs);
	}
	
	private static void showWarn(String cs)
	{
		if(ms_pluginMarker != null)
			ms_pluginMarker.warn(cs);
		ms_logger.warn(cs);
	}
	
	private static void showInfo(String cs)
	{
		if(ms_pluginMarker != null)
			ms_pluginMarker.info(cs);
		ms_logger.info(cs);
	}
	
	public static boolean canGenerateCheckNumberIndexes()
	{
		return ms_bGenerateCheckNumberIndexes;		
	}
	
	public static void enableGenerateCheckNumberIndexes()
	{
		ms_bGenerateCheckNumberIndexes = true;		
	}
	
	public static void disableGenerateCheckNumberIndexes()
	{
		ms_bGenerateCheckNumberIndexes = false;		
	}
	
	public static void resetReportLineCounter()
	{
		ms_nReportLineCounter = 0;
	}
	
	private static String getReportLineCounter()
	{
		ms_nReportLineCounter++;
		String cs = StringUtil.FormatWithFill4LeftZero(ms_nReportLineCounter);
		return cs;
	}
	
	public static void setLine(int n)
	{
		ms_nLastLine = n;
	}
	
	public static int getLastLine()
	{
		return ms_nLastLine;
	}
	
//	public static DCLGenConverter getDCLGenConverter()
//	{
//		return ms_DCLGenConverter;
//	}
	
	
	// PJD: Management of save maps
	private static CObjectCatalog ms_CurrentObjectCatalog = null; 
	public static void setCurrentObjectCatalog(CObjectCatalog o)
	{
		ms_CurrentObjectCatalog = o;
	}
 
	public static CObjectCatalog getCurrentObjectCatalog()
	{
		return ms_CurrentObjectCatalog;
	}

	public static void clearCurrentObjectCatalog()
	{
		ms_CurrentObjectCatalog = null;
	}
	
	public static void checkSQL(int nLine, String csQuery)
	{
		if (m_bSQLCheck)
		{
			Connection connection = getConnection();
			try
			{
				csQuery = csQuery.trim();
				SQLTypeOperation typeOperation = SQLTypeOperation.determineOperationType(csQuery, false);
				csQuery = SQLTypeOperation.updateMarkers(csQuery);
				csQuery = SQLTypeOperation.addEnvironmentPrefix("TEST", csQuery, typeOperation, "");
				PreparedStatement statement = connection.prepareStatement(csQuery);
				statement.getMetaData(); // to validate the SQL
				statement.close();
			}
			catch (SQLException ex)
			{
				ex.printStackTrace();
				if (csQuery.indexOf("SESSION.") == -1)
				{	
					logError(nLine, ex.getMessage());
				}
				else
				{
					logWarn(nLine, ex.getMessage());
				}
			}
		}
	}
	
	public static Connection getConnection()
	{
		if (m_connection == null)
		{	
			try
			{
				String url = "jdbc:db2://pub2000t.consultas.ch:50000/PUB2000T";
				String userId = "unact11t";
				String password = "pwd4t11t";
				String className = "com.ibm.db2.jcc.DB2Driver";
				
				Class.forName(className).newInstance();
				m_connection = DriverManager.getConnection(url, userId, password);
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				throw new RuntimeException(ex);
			}
		}
		return m_connection;
	}
	
	public static void releaseConnection()
	{
		if (m_connection != null)
		{
			try
			{
				m_connection.close();
				m_connection = null;
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				throw new RuntimeException(ex);
			}
		}
	}
	
	private void LoadDCLGENConverter(Tag eConf)
	{
		Tag tagEngines = eConf.getChild("Engines");
		if(tagEngines == null)
			return ;
		
		Tag tagDCLGDENConverter = tagEngines.getChild("DCLGDENConverter");
		if(tagDCLGDENConverter == null)
			return ;
		
		ms_DCLGenConverter = new DCLGenConverter();
		ms_DCLGenConverter.fill(tagDCLGDENConverter); 
	}
	
	
	public static SQLSyntaxConverter getSQLSyntaxConverter()
	{
		return ms_SQLSyntaxConverter;
	}
	
	public static int getNewSentenceIndex()
	{
		if(ms_bNextSentenceToLocate == false)
		{			
			ms_bNextSentenceToLocate = true;
			ms_nSentenceIndex++;
		}
		return ms_nSentenceIndex;
	}
		
	public static void resetNewSentenceIndex()
	{
		ms_nSentenceIndex = -1;
		ms_bNextSentenceToLocate = false;
	}
	
	public static void resetNextSentenceToLocate()
	{
		ms_bNextSentenceToLocate = false;
	}
	
	public static boolean isOracleTarget()
	{
		if(ms_DCLGenConverter  != null)
			return ms_DCLGenConverter.isOracleTarget();
		return false;			
	}
	
//	public static void registerOnceExecSQLDeclareTable(String csTableName, CExecSQLDeclareTable sqlDeclareTable)
//	{
//		if(ms_hashSQLDeclareTableByName == null)
//			ms_hashSQLDeclareTableByName = new Hashtable<String, CExecSQLDeclareTable>();
//		
//		if(csTableName.equals("TINTAMO"))
//		{
//			int gg =0;
//		}
//		CExecSQLDeclareTable table = ms_hashSQLDeclareTableByName.get(csTableName);
//		if(table == null)
//			ms_hashSQLDeclareTableByName.put(csTableName, sqlDeclareTable);
//		else
//		{
//			int gg = 0;
//		}
//	}
//	
//	public static CSQLTableColDescriptor getTableColumnDescription(String csTableName, String csColName)
//	{
//		if(ms_hashSQLDeclareTableByName == null)
//			return null;
//		
//		CExecSQLDeclareTable table = ms_hashSQLDeclareTableByName.get(csTableName);
//		if(table == null)
//			return null;
//		
//		CSQLTableColDescriptor colDescriptor = table.getColDescriptor(csColName);
//		return colDescriptor;
//	}
//	
//	private static CExecSQLDeclareTable getRegisteredExecSQLDeclareTable(String csTableName)
//	{
//		if(ms_hashSQLDeclareTableByName != null)
//		{
//			CExecSQLDeclareTable table = ms_hashSQLDeclareTableByName.get(csTableName);
//			return table;
//		}
//		return null;
//	}
//	
//	private static Hashtable<String, CExecSQLDeclareTable> ms_hashSQLDeclareTableByName = null;
}
