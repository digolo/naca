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
package generate.fpacjava;

import generate.CBaseLanguageExporter;
import semantic.CDataEntity;
import semantic.CDataEntity.CDataEntityType;
import semantic.Verbs.CEntitySubtractTo;
import utils.CObjectCatalog;

public class CFPacJavaSubtractTo extends CEntitySubtractTo
{

	public CFPacJavaSubtractTo(int line, CObjectCatalog cat, CBaseLanguageExporter out)
	{
		super(line, cat, out);
	}

	@Override
	protected void DoExport() {
		WriteWord("subtract(") ;
		WriteWord(this.m_Variable.ExportReference(getLine())) ;
		WriteWord(", ") ;
		WriteWord(this.m_Value.ExportReference(getLine())) ;
		WriteWord(").to(") ;
		WriteWord(this.m_Destination.ExportReference(getLine())) ;		
		WriteWord(") ;") ;
		WriteEOL() ;
	}
}
