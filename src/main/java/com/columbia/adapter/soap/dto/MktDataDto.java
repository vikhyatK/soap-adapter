package com.columbia.adapter.soap.dto;

public class MktDataDto {
	private Header Header;
	private MktData MktData;
	private Info Info;

	public Header getHeader() {
		return Header;
	}

	public void setHeader(Header header) {
		Header = header;
	}

	public MktData getMktData() {
		return MktData;
	}

	public void setMktData(MktData mktData) {
		MktData = mktData;
	}

	public Info getInfo() {
		return Info;
	}

	public void setInfo(Info info) {
		Info = info;
	}
	
	
}
