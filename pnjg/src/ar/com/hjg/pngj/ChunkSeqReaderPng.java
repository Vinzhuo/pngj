package ar.com.hjg.pngj;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ar.com.hjg.pngj.ChunkReader.ChunkReaderMode;
import ar.com.hjg.pngj.chunks.ChunkHelper;
import ar.com.hjg.pngj.chunks.ChunksList;
import ar.com.hjg.pngj.chunks.PngChunk;
import ar.com.hjg.pngj.chunks.PngChunk.ChunkOrderingConstraint;
import ar.com.hjg.pngj.chunks.ChunkFactory;
import ar.com.hjg.pngj.chunks.PngChunkIDAT;
import ar.com.hjg.pngj.chunks.PngChunkIEND;
import ar.com.hjg.pngj.chunks.PngChunkIHDR;
import ar.com.hjg.pngj.chunks.PngChunkPLTE;

/**
 * Adds to ChunkSeqReader the storing of PngChunk s , with the PngFactory, and imageInfo + deinterlacer
 * 
 * Most usual PNG reading should use this.
 */
public class ChunkSeqReaderPng extends ChunkSeqReader {

	protected ImageInfo imageInfo; // initialized at parsing the IHDR
	protected Deinterlacer deinterlacer;
	protected int currentChunkGroup = -1;
	
	/**
	 * All chunks, but some of them can have the buffer empty (IDAT and skipped)
	 */
	protected ChunksList chunksList = null;
	protected final boolean callbackMode;
	private long bytesChunksLoaded = 0; // bytes loaded from buffered chunks non-critical chunks (data only)
	private boolean checkCrc=true;
	
	// --- parameters to be set prior to reading ---
	private boolean includeNonBufferedChunks=false;
	
	private Set<String> chunksToSkip = new HashSet<String>();
	private long maxTotalBytesRead = 0;
	private long skipChunkMaxSize = 0;
	private long maxBytesMetadata = 0;
	
	
	public ChunkSeqReaderPng(boolean callbackMode) {
		super();
		this.callbackMode=callbackMode;
	}
	
	private void updateAndCheckChunkGroup(String id) {
		if (id.equals(PngChunkIHDR.ID)) { // IDHR
			if (currentChunkGroup < 0)
				currentChunkGroup = ChunksList.CHUNK_GROUP_0_IDHR;
			else
				throw new PngjInputException("unexpected chunk " + id);
		} else if (id.equals(PngChunkPLTE.ID)) { // PLTE
			if ((currentChunkGroup == ChunksList.CHUNK_GROUP_0_IDHR || currentChunkGroup == ChunksList.CHUNK_GROUP_1_AFTERIDHR))
				currentChunkGroup = ChunksList.CHUNK_GROUP_2_PLTE;
			else
				throw new PngjInputException("unexpected chunk " + id);
		} else if (id.equals(PngChunkIDAT.ID)) { // IDAT (no necessarily the first)
			if ((currentChunkGroup >= ChunksList.CHUNK_GROUP_0_IDHR && currentChunkGroup <= ChunksList.CHUNK_GROUP_4_IDAT))
				currentChunkGroup = ChunksList.CHUNK_GROUP_4_IDAT;
			else
				throw new PngjInputException("unexpected chunk " + id);
		} else if (id.equals(PngChunkIEND.ID)) { // END
			if ((currentChunkGroup >= ChunksList.CHUNK_GROUP_4_IDAT))
				currentChunkGroup = ChunksList.CHUNK_GROUP_6_END;
			else
				throw new PngjInputException("unexpected chunk " + id);
		} else { // ancillary
			if (currentChunkGroup <= ChunksList.CHUNK_GROUP_1_AFTERIDHR)
				currentChunkGroup = ChunksList.CHUNK_GROUP_1_AFTERIDHR;
			else if (currentChunkGroup <= ChunksList.CHUNK_GROUP_3_AFTERPLTE)
				currentChunkGroup = ChunksList.CHUNK_GROUP_3_AFTERPLTE;
			else
				currentChunkGroup = ChunksList.CHUNK_GROUP_5_AFTERIDAT;
		}
	}

	@Override
	public boolean shouldSkipContent(int len, String id) {
		if(super.shouldSkipContent(len, id)) return true;
		if (maxTotalBytesRead > 0 && len + bytesCount > maxTotalBytesRead)
			throw new PngjInputException("Maximum total bytes to read exceeeded: " + maxTotalBytesRead + " offset:"
					+ bytesCount + " len=" + len);
		if (chunksToSkip.contains(id))
			return true; // specific skip
		if (skipChunkMaxSize > 0 && len > skipChunkMaxSize)
			return true; // too big chunk
		if (maxBytesMetadata > 0 && len > maxBytesMetadata - bytesChunksLoaded && !ChunkHelper.isCritical(id))
			return true; // too much ancillary chunks loaded 
		return false;
	}
	
	public long getBytesChunksLoaded() {
		return bytesChunksLoaded;
	}

	public int getCurrentChunkGroup() {
		return currentChunkGroup;
	}

	public void setChunksToSkip(String... chunksToSkip) {
		this.chunksToSkip.clear();
		for (String c : chunksToSkip)
			this.chunksToSkip.add(c);
	}

	public void addChunkToSkip(String chunkToSkip) {
		this.chunksToSkip.add(chunkToSkip);
	}



	public boolean firstChunksNotYetRead() {
		return getCurrentChunkGroup() < ChunksList.CHUNK_GROUP_4_IDAT;
	}
	
	@Override
	protected void processChunk(ChunkReader chunkR) {
		super.processChunk(chunkR);
		if (chunkR.getChunkRaw().id.equals(PngChunkIHDR.ID)) {
			PngChunkIHDR ch = new PngChunkIHDR(null);
			ch.parseFromRaw(chunkR.getChunkRaw());
			imageInfo = ch.createImageInfo();
			if(ch.isInterlaced()) 
				deinterlacer = new Deinterlacer(imageInfo);
			chunksList=new ChunksList(imageInfo);
		}
		if(chunkR.mode == ChunkReaderMode.BUFFER|| includeNonBufferedChunks) {
			PngChunk chunk=getChunkFactory().createChunk(chunkR.getChunkRaw(),getImageInfo());
			chunksList.appendReadChunk(chunk, currentChunkGroup);
		}
		if(isDone()) {		
			processEndPng();
		}
	}

	/** check that the last inserted chunk had the correct ordering */
	protected void checkOrdering() {
		PngChunk c = chunksList.getChunks().get(chunksList.getChunks().size()-1);
		ChunkOrderingConstraint oc = c.getOrderingConstraint();
		//chunksList.getById1();
		PngHelperInternal.LOGGER.warning("check ordering not implemented");
		
	}

	@Override
	protected DeflatedChunksSet createIdatSet(String id) {
		IdatSet ids = new IdatSet(id,imageInfo, deinterlacer);
		ids.setCallbackMode(callbackMode);
		return ids;
	}

	public IdatSet getIdatSet() {
		DeflatedChunksSet c = getCurReaderDeflatedSet();
		return c instanceof IdatSet ? (IdatSet) c : null;
	}

	@Override
	protected boolean isIdatKind(String id) {
		return id.equals(PngChunkIDAT.ID);
	}
	
	@Override
	public int feed(byte[] buf, int off, int len) {
		return super.feed(buf, off, len);
	}

	public IChunkFactory getChunkFactory() {
		return new ChunkFactory();
	}

	/**
	 * Things to be done after IEND processing. This is not called if prematurely closed.
	 */
	protected void processEndPng() {
		// nothing to do
	}

	public ImageInfo getImageInfo() {
		return imageInfo;
	}	
	public boolean isInterlaced() {
		return deinterlacer != null;
	}

	public Deinterlacer getDeinterlacer() {
		return deinterlacer;
	}


	
	@Override
	protected void startNewChunk(int len, String id,long offset) {
		updateAndCheckChunkGroup(id);
		super.startNewChunk(len, id,offset);
	}

	@Override
	public void close() {
		if (currentChunkGroup != ChunksList.CHUNK_GROUP_6_END)// this could only happen if forced close
			currentChunkGroup = ChunksList.CHUNK_GROUP_6_END;
		super.close();
	}

	public List<PngChunk> getChunks() {
		return chunksList.getChunks();
	}
	
	
	public void setMaxTotalBytesRead(long maxTotalBytesRead) {
		this.maxTotalBytesRead = maxTotalBytesRead;
	}

	public long getSkipChunkMaxSize() {
		return skipChunkMaxSize;
	}

	public void setSkipChunkMaxSize(long skipChunkMaxSize) {
		this.skipChunkMaxSize = skipChunkMaxSize;
	}

	public long getMaxBytesMetadata() {
		return maxBytesMetadata;
	}

	public void setMaxBytesMetadata(long maxBytesMetadata) {
		this.maxBytesMetadata = maxBytesMetadata;
	}

	public long getMaxTotalBytesRead() {
		return maxTotalBytesRead;
	}

	@Override
	protected boolean shouldCheckCrc(int len, String id) {
		return checkCrc;
	}

	public boolean isCheckCrc() {
		return checkCrc;
	}

	public void setCheckCrc(boolean checkCrc) {
		this.checkCrc = checkCrc;
	}

	public boolean isCallbackMode() {
		return callbackMode;
	}

	public Set<String> getChunksToSkip() {
		return chunksToSkip;
	}

	/**
	 * If true, the chunks with no data (because skipped or because processed like IDAT-type)
	 * are still stored in the PngChunks list, which might be more informative.
	 *  
	 * Setting this to false saves a few bytes
	 * 
	 * Default: false
	 * 
	 * @param includeNonBufferedChunks 
	 */
	public void setIncludeNonBufferedChunks(boolean includeNonBufferedChunks) {
		this.includeNonBufferedChunks = includeNonBufferedChunks;
	}

	
}
