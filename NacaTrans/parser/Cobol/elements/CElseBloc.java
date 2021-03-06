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
 * Created on Jul 19, 2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package parser.Cobol.elements;

import lexer.*;
import lexer.Cobol.CCobolKeywordList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;



/**
 * @author U930CV
 *
 * To change the template for this generated type comment go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
public class CElseBloc extends CBlocElement
{
	/**
	 * @param line
	 */
	public CElseBloc(int line) {
		super(line);
	}
	/* (non-Javadoc)
	 * @see parser.CLanguageElement#ExportCustom(org.w3c.dom.Document)
	 */
	protected boolean DoParsing(CFlag fCheckForNextSentence)
	{
		CBaseToken tok = GetCurrentToken() ;
		if (tok.GetType()== CTokenType.KEYWORD && tok.GetKeyword() == CCobolKeywordList.ELSE)
		{
			GetNext();
		} 
		if (!super.DoParsing())
		{
			return false ;
		}
		if (fCheckForNextSentence != null)
		{
			fCheckForNextSentence.Set(m_fCheckForNextSentence.ISSet()) ;
		}
		tok = GetCurrentToken() ;
		if (tok.GetType() == CTokenType.DOT)
		{ // end of IF statement
			m_nEndLine = tok.getLine() ;
			return true ; 
		}
		else if (tok.GetKeyword() == CCobolKeywordList.END_IF)
		{
			m_nEndLine = tok.getLine() ;
			StepNext() ;
		}
		return true ;
	}
	protected Element ExportCustom(Document root)
	{
		Element eElse = root.createElement("Else") ;
		return eElse ;
	}
	/* (non-Javadoc)
	 * @see parser.elements.CBlocElement#isTopLevelBloc()
	 */
	protected boolean isTopLevelBloc()
	{
		return false;
	}
}
