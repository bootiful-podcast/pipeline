# pipeline

Deploys Bootiful Podcast's applications to Cloud Foundry from the container images built by their own repositories.
Today that is one app, `site` (from [`site-v2`](https://github.com/bootiful-podcast/site-v2)), which serves `https://bootifulpodcast.fm`.
The foundation is mypaas, from `~/code/application-concierge/bosh-platform-installer` (`mypaas.env`, `api.cf.mypaas.online`), and the apps live in the org `bootiful-podcast`, space `production`.

## How a change gets deployed

1. A push to an app repository's `main` branch builds its container image and pushes it to `us-docker.pkg.dev/joshlong/bootiful-podcast-artifact-registry/<app>:latest`.
2. That workflow then sends an `update-event` repository dispatch to this repository.
3. `.github/workflows/deploy-to-production.yaml` runs `bin/deploy.sh` with the vars file its `VARS_FILE` names, `vars/mypaas.yml`, which also holds the foundation's API (`cf-api`), and pushes every app in `manifest.yml`.

The deploy workflow also runs on a push to this repository's `main` branch, and by hand.
Concurrent deploys queue, so a later build cannot overlap a deployment already in progress.

## Deploying by hand

```bash
cf login -a https://api.cf.mypaas.online -u josh
cd ~/code/bootiful-podcast/pipeline
bw-unlock                      # for the image pull key
bin/deploy.sh                  # every app, or name some: bin/deploy.sh site
```

`bin/deploy.sh` pins each app's `:latest` to its current digest, and runs `cf push --strategy rolling` for each app named, into the org and space the vars file names.
`vars/mypaas.yml` is the default; `--vars vars/<foundation>.yml` names another.

## Files

| File | Holds |
|---|---|
| `manifest.yml` | every app; every value that differs by foundation is a `((var))` |
| `vars/mypaas.yml` | the foundation's API, and the org, space, registry, domains and hosts, for mypaas; no secrets |
| `bin/deploy.sh` | resolves digests, targets the org and space, pushes |
| `.github/workflows/deploy-to-production.yaml` | the GitHub Actions deployment |

## Adding an app

1. Give the app's repository a workflow that builds `us-docker.pkg.dev/joshlong/bootiful-podcast-artifact-registry/<app>:latest` and then sends the `update-event` dispatch, as `site-v2/.github/workflows/build-container.yaml` does.
2. Add the app to `manifest.yml`, with `image: ((registry))/<app>@((<app>-digest))`; `bin/deploy.sh` resolves the digest of every app in the manifest.
3. Add its hosts to `vars/mypaas.yml`, and any secrets it needs as Actions secrets mapped into the workflow's `env`.

## Credentials

| Secret | On | Is | Kept in Bitwarden as |
|---|---|---|---|
| `CF_USERNAME`, `CF_PASSWORD` | this repository | the CF user `josh` on mypaas, OrgManager of `bootiful-podcast` and SpaceDeveloper in `bootiful-podcast` / `production` | |
| `CF_DOCKER_PASSWORD` | this repository | a JSON key of the service account `bootiful-podcast-cf-puller@joshlong.iam.gserviceaccount.com`, which can only read `bootiful-podcast-artifact-registry` | `bootiful-podcast-cf-image-puller-key` (notes) |
| `GCLOUD_SA_KEY` | this repository, and `site-v2` | a JSON key of `bootiful-podcast-ci@joshlong.iam.gserviceaccount.com`, which can only push to and read `bootiful-podcast-artifact-registry`; `site-v2` pushes images with it, and this repository reads their digests | |
| `BOOTIFUL_PODCAST_GITHUB_PERSONAL_ACCESS_TOKEN` | each app repository | a token that can send the dispatch to this repository | `mogul-github-pat` |

The images are private.
`cf push` gives the cell a pull credential: username `_json_key`, password the service account key.
Locally, `bin/deploy.sh` reads the key from Bitwarden, so the vault has to be unlocked; `CF_DOCKER_PASSWORD`, if set, takes precedence.
If the key is ever lost, make a new one (`gcloud iam service-accounts keys create`), save it to the same item and the Actions secret, and delete the old key.

## DNS

`bootifulpodcast.fm` is registered at Gandi, and its DNS is hosted by Gandi LiveDNS.
It is a private domain of the `bootiful-podcast` org on mypaas.
Its certificate (Certificate Manager, on the foundation's load balancer) and its `A` records were set up with the installer's `app-domain.sh`:

```bash
cd ~/code/application-concierge/bosh-platform-installer
GANDI_PAT=... ./app-domain.sh --env mypaas.env --org bootiful-podcast --space production --app site --hostname @ bootifulpodcast.fm
```

Any other name under the domain, such as `www.bootifulpodcast.fm`, needs a route and a certificate entry: add the route to the app in `manifest.yml`, deploy, and run `app-domain.sh` again with `--hostname www`.
Records other than the platform's own `A` records, such as `MX` or `TXT`, go in Gandi LiveDNS.
