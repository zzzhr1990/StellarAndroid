package org.bluray.ti;

import javax.tv.locator.Locator;
import javax.tv.service.SIException;
import javax.tv.service.SIRequest;
import javax.tv.service.SIRequestor;
import javax.tv.service.ServiceType;

import org.bluray.net.BDLocator;
import org.davic.net.InvalidLocatorException;
import org.videolan.Libbluray;
import org.videolan.TitleInfo;
import org.videolan.bdjo.Bdjo;

public class TitleImpl implements Title {
    public TitleImpl(int titleNum) throws SIException {
        this.titleNum = titleNum;
        this.ti = Libbluray.getTitleInfo(titleNum);
        if (ti == null)
            throw new SIException("Title " + titleNum + " does not exist in disc index");
    }

    public PlayList[] getPlayLists() {
        if (!getBdjo())
            return new PlayList[0];

        org.videolan.bdjo.PlayListTable plt = bdjo.getAccessiblePlaylists();

        // If accessToAll flag is set, enumerate all playlists on the disc
        if (plt.isAccessToAll()) {
            String[] files = Libbluray.listBdFiles("BDMV" + java.io.File.separator + "PLAYLIST", false);
            if (files == null) {
                return new PlayList[0];
            }

            java.util.Vector allPlaylists = new java.util.Vector();
            for (int i = 0; i < files.length; i++) {
                String file = files[i];
                if (file.toLowerCase().endsWith(".mpls")) {
                    String name = file.substring(0, file.length() - 5);
                    allPlaylists.addElement(new PlayListImpl(name, this));
                }
            }
            PlayList[] playlists = new PlayList[allPlaylists.size()];
            allPlaylists.copyInto(playlists);
            return playlists;
        }

        String[] playlistNames = plt.getPlayLists();
        PlayList[] playlists = new PlayList[playlistNames.length];
        for (int i = 0; i < playlistNames.length; i++)
            playlists[i] = new PlayListImpl(playlistNames[i], this);

        return playlists;
    }

    public boolean hasAutoPlayList() {
        if (!getBdjo())
            return false;
        return bdjo.getAccessiblePlaylists().isAutostartFirst();
    }

    public Locator getLocator() {
        try {
            return new BDLocator(null, titleNum, -1);
        } catch (InvalidLocatorException ex) {
            return null;
        }
    }

    public String getName() {
        if (titleNum == 0)
            return "Top Menu";
        if (titleNum == 65535)
            return "First Playback";
        if (titleNum == 65534)
            return "Suspended Title";
        return "Title " + titleNum;
    }

    public ServiceType getServiceType() {
        switch (ti.getPlaybackType()) {
        case TitleInfo.HDMV_PLAYBACK_TYPE_MOVIE:
            return TitleType.HDMV_MOVIE;
        case TitleInfo.HDMV_PLAYBACK_TYPE_INTERACTIVE:
            return TitleType.HDMV_INTERACTIVE;
        case TitleInfo.BDJ_PLAYBACK_TYPE_MOVIE:
            return TitleType.BDJ_MOVIE;
        case TitleInfo.BDJ_PLAYBACK_TYPE_INTERACTIVE:
            return TitleType.BDJ_INTERACTIVE;
        }
        return TitleType.UNKNOWN;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof TitleImpl)) {
            return false;
        }
        TitleImpl other = (TitleImpl)obj;
        int otherNum = other.getTitleNum();
        return otherNum == titleNum;
    }

    public int hashCode() {
        return titleNum % 1023;
    }

    public boolean hasMultipleInstances() {
        return false;
    }

    public SIRequest retrieveDetails(SIRequestor requestor) {
        //TODO
        org.videolan.Logger.unimplemented(TitleImpl.class.getName(), "retrieveDetails");
        return null;
    }

    public int getTitleNum() {
        return titleNum;
    }

    public TitleInfo getTitleInfo() {
        return ti;
    }

    private boolean getBdjo() {
        if (ti.isBdj() && bdjo == null) {
            bdjo = Libbluray.getBdjo(ti.getBdjoName());
            if (bdjo == null) {
                org.videolan.Logger.getLogger(TitleImpl.class.getName()).error("title " + titleNum + ": Failed loading " + ti.getBdjoName() + ".bdjo");
            }
        }
        return bdjo != null;
    }

    private int titleNum;
    private TitleInfo ti;
    private Bdjo bdjo = null;
}
