package com.infmme.readilyapp.readable.epub;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.infmme.readilyapp.provider.cachedbook.CachedBookColumns;
import com.infmme.readilyapp.provider.cachedbook.CachedBookContentValues;
import com.infmme.readilyapp.provider.cachedbook.CachedBookCursor;
import com.infmme.readilyapp.provider.cachedbook.CachedBookSelection;
import com.infmme.readilyapp.provider.epubbook.EpubBookContentValues;
import com.infmme.readilyapp.provider.epubbook.EpubBookSelection;
import com.infmme.readilyapp.readable.Readable;
import com.infmme.readilyapp.readable.interfaces.*;
import com.infmme.readilyapp.reader.Reader;
import com.infmme.readilyapp.util.ColorMatcher;
import com.infmme.readilyapp.util.Constants;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Metadata;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static com.infmme.readilyapp.readable.epub.EpubPart.parseRawText;

/**
 * Created with love, by infm dated on 6/8/16.
 */

public class EpubStorable implements Storable, Chunked, Unprocessed,
    Structured {
  private String mPath;
  private long mFileSize;

  /**
   * Creation time in order to keep track of db records.
   * Has joda LocalDateTime format.
   */
  private String mTimeOpened;

  private Book mBook;
  private transient Metadata mMetadata;

  // Stored title.
  private String mTitle = null;
  private Double mPercentile = .0;

  private String mCoverImageUri;
  private Integer mCoverImageMean = null;
  private transient List<Resource> mContents;

  private Deque<ChunkInfo> mLoadedChunks = new ArrayDeque<>();
  private List<? extends AbstractTocReference> mTableOfContents = null;

  private String mCurrentResourceId;
  private int mCurrentResourceIndex = -1;
  private int mLastResourceIndex = -1;

  private int mCurrentTextPosition;
  private double mChunkPercentile = .0;

  private boolean mProcessed;

  // Again, can be leaking.
  private transient Context mContext = null;

  public EpubStorable(Context context) {
    mContext = context;
  }

  public EpubStorable(Context context, String timeCreated) {
    mContext = context;
    mTimeOpened = timeCreated;
  }

  @Override
  public Reading readNext() throws IOException {
    Readable readable = new Readable();
    if (mLastResourceIndex == -1) {
      for (int i = 0; i < mContents.size() &&
          mLastResourceIndex == -1; ++i) {
        if (mCurrentResourceId.equals(mContents.get(i).getId())) {
          mLastResourceIndex = i;
        }
      }
    }
    mCurrentResourceIndex = mLastResourceIndex;

    String parsed = parseRawText(
        new String(mContents.get(mLastResourceIndex).getData()));
    readable.setText(parsed);

    mLoadedChunks.addLast(new ChunkInfo(mCurrentResourceIndex));
    mLastResourceIndex++;
    return readable;
  }

  @Override
  public boolean hasNextReading() {
    return mContents != null && mLastResourceIndex < mContents.size();
  }

  @Override
  public void skipLast() {
    mLoadedChunks.removeLast();
  }

  @Override
  public boolean isStoredInDb() {
    CachedBookSelection where = new CachedBookSelection();
    where.path(mPath);
    Cursor c = mContext.getContentResolver()
                       .query(CachedBookColumns.CONTENT_URI,
                              new String[] { CachedBookColumns._ID },
                              where.sel(), where.args(), null);
    boolean result = true;
    if (c != null) {
      CachedBookCursor book = new CachedBookCursor(c);
      if (book.getCount() < 1) {
        result = false;
      }
      book.close();
    } else {
      result = false;
    }
    return result;
  }

  @Override
  public void readFromDb() {
    if (isStoredInDb()) {
      CachedBookSelection where = new CachedBookSelection();
      where.path(mPath);
      Cursor c = mContext.getContentResolver()
                         .query(CachedBookColumns.CONTENT_URI,
                                CachedBookColumns.ALL_COLUMNS_EPUB_JOINED,
                                where.sel(), where.args(), null);
      if (c == null) {
        throw new RuntimeException("Unexpected cursor fail.");
      } else if (c.moveToFirst()) {
        CachedBookCursor book = new CachedBookCursor(c);
        mTitle = book.getTitle();
        mTimeOpened = book.getTimeOpened();
        mPercentile = book.getPercentile();
        mCurrentTextPosition = book.getEpubBookTextPosition();
        mCurrentResourceId = book.getEpubBookCurrentResourceId();
        book.close();
      } else {
        c.close();
      }
    } else {
      throw new IllegalStateException("Not stored in a db yet!");
    }
  }

  @Override
  public Storable prepareForStoringSync(Reader reader) {
    if (mLoadedChunks != null && !mLoadedChunks.isEmpty()) {
      mCurrentResourceIndex = mLoadedChunks.getFirst().mResourceIndex;
      mCurrentResourceId = mContents.get(mCurrentResourceIndex).getId();
      mChunkPercentile = reader.getPercentile();
      setCurrentPosition(reader.getPosition());
    }
    return this;
  }

  @Override
  public void beforeStoringToDb() {
    mContents = mBook.getContents();
    if (coverImageExists() && !isCoverImageStored()) {
      try {
        // TODO: Figure out which thread it belongs to.
        storeCoverImage();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void storeToDb() {
    CachedBookContentValues values = new CachedBookContentValues();
    double percent = calcPercentile();

    EpubBookContentValues epubValues = new EpubBookContentValues();
    epubValues.putCurrentResourceId(mCurrentResourceId);
    epubValues.putTextPosition(mCurrentTextPosition);

    if (isStoredInDb()) {
      CachedBookSelection cachedWhere = new CachedBookSelection();
      cachedWhere.path(mPath);
      if (percent >= 0 && percent <= 1) {
        values.putPercentile(calcPercentile());
        values.update(mContext, cachedWhere);
      }
      EpubBookSelection epubWhere = new EpubBookSelection();
      epubWhere.id(getFkEpubBookId());
      epubValues.update(mContext, epubWhere);
    } else {
      if (percent >= 0 && percent <= 1) {
        values.putPercentile(calcPercentile());
      } else {
        values.putPercentile(0);
      }
      values.putTimeOpened(mTimeOpened);
      values.putPath(mPath);
      values.putTitle(mTitle);
      values.putCoverImageUri(mCoverImageUri);
      values.putCoverImageMean(mCoverImageMean);

      Uri uri = epubValues.insert(mContext.getContentResolver());
      long epubId = Long.parseLong(uri.getLastPathSegment());
      values.putEpubBookId(epubId);
      values.insert(mContext.getContentResolver());
    }
  }

  /**
   * Uses uniqueness of a path to get epub_book_id from a cached_book table.
   *
   * @return epub_book_id for an mPath.
   */
  private Long getFkEpubBookId() {
    Long id = null;

    CachedBookSelection cachedWhere = new CachedBookSelection();
    cachedWhere.path(mPath);
    CachedBookCursor cachedBookCursor =
        new CachedBookCursor(mContext.getContentResolver().query(
            CachedBookColumns.CONTENT_URI,
            new String[] { CachedBookColumns.EPUB_BOOK_ID },
            cachedWhere.sel(), cachedWhere.args(), null));
    if (cachedBookCursor.moveToFirst()) {
      id = cachedBookCursor.getEpubBookId();
    }
    cachedBookCursor.close();
    return id;
  }

  /**
   * May be very heavy, need to think if it's needed at all.
   *
   * @return Percent progress of reading this book.
   */
  private double calcPercentile() {
    if (mCurrentResourceIndex != -1) {
      List<Resource> passedResources =
          mContents.subList(0, mCurrentResourceIndex);
      long bytesPassed = 0;
      for (Resource r : passedResources) {
        bytesPassed += r.getSize();
      }
      long nextResBytes = bytesPassed +
          mContents.get(mCurrentResourceIndex).getSize();
      return (double) bytesPassed / mFileSize + mChunkPercentile *
          (nextResBytes - bytesPassed) / mFileSize;
    }
    return -1;
  }

  @Override
  public void storeToFile() {
    throw new IllegalStateException(
        "You can't store EpubStorable to a filesystem.");
  }

  @Override
  public Storable readFromFile() throws IOException {
    if (mPath != null) {
      mBook = (new EpubReader()).readEpubLazy(
          mPath, Constants.DEFAULT_ENCODING);
      mFileSize = new File(mPath).length();
    } else {
      throw new IllegalStateException("No path to read file from.");
    }
    return this;
  }

  @Override
  public String getPath() {
    return mPath;
  }

  @Override
  public void setPath(String path) {
    mPath = path;
  }

  @Override
  public String getTitle() {
    if (mMetadata != null) {
      return mMetadata.getFirstTitle();
    }
    return null;
  }

  @Override
  public void setTitle(String title) {
    mMetadata.getTitles().add(0, title);
  }

  @Override
  public void onReaderNext() {
    mLoadedChunks.removeFirst();
  }

  @Override
  public boolean isProcessed() {
    return mProcessed;
  }

  @Override
  public void setProcessed(boolean processed) {
    mProcessed = processed;
  }

  public Context getContext() {
    return mContext;
  }

  public void setContext(Context mContext) {
    this.mContext = mContext;
  }

  @Override
  public void process() {
    try {
      if (mBook == null) {
        readFromFile();
      }
      mContents = mBook.getContents();
      mMetadata = mBook.getMetadata();
      if (isStoredInDb()) {
        readFromDb();
      } else {
        mCurrentTextPosition = 0;
        mCurrentResourceId = mContents.get(0).getId();
        mTitle = mMetadata.getFirstTitle();
      }
      mProcessed = true;
    } catch (IOException e) {
      e.printStackTrace();
      mProcessed = false;
    }
  }

  @Override
  public List<? extends AbstractTocReference> getTableOfContents() {
    if (mTableOfContents == null && mProcessed) {
      mTableOfContents = EpubPart.adaptList(
          mBook.getTableOfContents().getTocReferences());
    }
    return mTableOfContents;
  }

  @Override
  public String getCurrentId() {
    return mCurrentResourceId;
  }

  @Override
  public void setCurrentTocReference(AbstractTocReference tocReference) {
    EpubPart epubPart = (EpubPart) tocReference;
    mCurrentResourceId = epubPart.getId();
  }

  @Override
  public int getCurrentPosition() {
    return mCurrentTextPosition;
  }

  @Override
  public void setCurrentPosition(int position) {
    mCurrentTextPosition = position;
  }

  private boolean coverImageExists() {
    return mBook.getCoverImage() != null;
  }

  private boolean isCoverImageStored() {
    Resource coverImage = mBook.getCoverImage();
    return new File(getCoverImagePath(coverImage)).exists();
  }

  private void storeCoverImage() throws IOException {
    Resource coverImage = mBook.getCoverImage();
    byte[] imageBytes = coverImage.getData();
    String coverImagePath = getCoverImagePath(coverImage);
    FileOutputStream fos = new FileOutputStream(coverImagePath);
    fos.write(imageBytes);
    fos.close();
    mCoverImageUri = coverImagePath;
    mCoverImageMean = ColorMatcher.pickRandomMaterialColor();
  }

  private String getCoverImagePath(final Resource coverImage) {
    return mContext.getCacheDir() + mPath.substring(
        mPath.lastIndexOf('/')) + coverImage.getHref();
  }

  private class ChunkInfo implements Serializable {
    public int mResourceIndex;

    public ChunkInfo(int resourceIndex) {
      this.mResourceIndex = resourceIndex;
    }
  }
}
