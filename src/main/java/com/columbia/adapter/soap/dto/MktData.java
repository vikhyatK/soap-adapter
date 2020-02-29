package com.columbia.adapter.soap.dto;

import java.util.List;

public class MktData {
	private String Symbol;
	private Double LastPx;
	private List<MDEntryPx> MDEntryPx;
	public String getSymbol() {
		return Symbol;
	}
	public void setSymbol(String symbol) {
		Symbol = symbol;
	}
	public Double getLastPx() {
		return LastPx;
	}
	public void setLastPx(Double lastPx) {
		LastPx = lastPx;
	}
	public List<MDEntryPx> getMDEntryPx() {
		return MDEntryPx;
	}
	public void setMDEntryPx(List<MDEntryPx> mDEntryPx) {
		MDEntryPx = mDEntryPx;
	}
	
}
