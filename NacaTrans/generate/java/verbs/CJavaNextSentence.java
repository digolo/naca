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
/*
 * Created on 9 ao�t 2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package generate.java.verbs;

import generate.CBaseLanguageExporter;
import semantic.Verbs.CEntityNextSentence;
import utils.CObjectCatalog;
import utils.Transcoder;

/**
 * @author sly
 *
 * To change the template for this generated type comment go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
public class CJavaNextSentence extends CEntityNextSentence
{

	/**
	 * @param line
	 * @param cat
	 * @param out
	 */
	public CJavaNextSentence(int line, CObjectCatalog cat, CBaseLanguageExporter out, String csReference)
	{
		super(line, cat, out);
		m_csReference = csReference;
	}
	
	private String m_csReference = null;

	/* (non-Javadoc)
	 * @see semantic.CBaseLanguageEntity#DoExport()
	 */
	protected void DoExport()
	{
		String label = FormatIdentifier(m_csReference) ;
		String cs = "nextSentence(" + label + ");";
		WriteLine(cs) ;
	}

}
