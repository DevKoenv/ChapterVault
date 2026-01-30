# Legal Connector Sources

This document lists manga/comic sources that can be legally integrated into ChapterVault. When creating connectors, only target sources that:

1. Offer free, legal content (official publishers, creator-authorized platforms)
2. Have terms of service that allow personal archival/backup
3. Do not require circumventing DRM or other technical protection measures

---

## Official Publisher Platforms

These platforms are operated by or licensed by publishers and offer legal content:

| Source                                           | Type     | Notes                                       |
|--------------------------------------------------|----------|---------------------------------------------|
| [MangaPlus](https://mangaplus.shueisha.co.jp/)   | Manga    | Shueisha's official platform, free chapters |
| [MANGA Plus Creators](https://medibang.com/mpc/) | Manga    | Creator-uploaded content                    |
| [Webtoon](https://www.webtoons.com/)             | Webtoon  | Official platform, free tier available      |
| [Tapas](https://tapas.io/)                       | Webcomic | Official platform, free content available   |
| [Lezhin Comics](https://www.lezhin.com/)         | Webtoon  | Some free content                           |
| [Tappytoon](https://www.tappytoon.com/)          | Webtoon  | Some free content                           |
| [Azuki](https://www.azuki.co/)                   | Manga    | Licensed manga platform                     |
| [ComicWalker](https://comic-walker.com/)         | Manga    | Kadokawa's official platform                |
| [Pixiv Comics](https://comic.pixiv.net/)         | Manga    | Creator-uploaded content                    |
| [Naver Webtoon](https://comic.naver.com/)        | Webtoon  | Korean platform, some free content          |
| [KakaoPage](https://page.kakao.com/)             | Webtoon  | Korean platform                             |
| [LINE Manga](https://manga.line.me/)             | Manga    | Japanese platform                           |

## Open/Creative Commons Sources

Platforms with openly licensed or public domain content:

| Source                                                     | Type     | Notes                                     |
|------------------------------------------------------------|----------|-------------------------------------------|
| [Comic Book Plus](https://comicbookplus.com/)              | Comics   | Public domain golden age comics           |
| [Digital Comic Museum](https://digitalcomicmuseum.com/)    | Comics   | Public domain comics archive              |
| [The Internet Archive](https://archive.org/details/comics) | Comics   | Various public domain and openly licensed |
| [Pepper&Carrot](https://www.peppercarrot.com/)             | Webcomic | CC-BY licensed                            |
| [XKCD](https://xkcd.com/)                                  | Webcomic | CC-BY-NC licensed                         |

## Self-Hosted / Personal Use

Connectors for personal library management:

| Source                                  | Type    | Notes                             |
|-----------------------------------------|---------|-----------------------------------|
| [Komga](https://komga.org/)             | Library | Connect to your own Komga server  |
| [Kavita](https://www.kavitareader.com/) | Library | Connect to your own Kavita server |
| Local filesystem                        | Files   | Import local CBZ/CBR files        |

---

## Important Guidelines

### Do NOT Create Connectors For:

- Piracy/scanlation sites (aggregators hosting unlicensed content)
- Sites that primarily host fan-translated content without publisher permission
- Platforms where content requires bypassing paywalls
- Any source requiring DRM circumvention

### When Creating a Connector:

1. **Check the ToS** - Ensure the site allows automated access for personal use
2. **Respect rate limits** - Don't hammer servers; use appropriate delays
3. **Identify yourself** - Use a proper User-Agent string
4. **Cache appropriately** - Don't re-download content unnecessarily
5. **Support the creators** - Link to official purchase options when available

### Legal Considerations:

- Personal backup/archival is generally permitted in many jurisdictions
- Redistribution of downloaded content is NOT permitted
- Always respect copyright and licensing terms
- When in doubt, contact the platform's support

---

## Adding a New Source

Before implementing a connector for a new source:

1. Verify the source is listed above OR
2. Create an issue to discuss adding a new legal source
3. Document the legal basis for inclusion
4. Get maintainer approval before submitting a PR

---

## Disclaimer

This list is provided for informational purposes. Laws vary by jurisdiction. Users are responsible for ensuring their use complies with local laws and platform terms of service.
