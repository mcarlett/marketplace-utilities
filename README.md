Marketplace Utilities
=====================

## What is it for

This library is intended for automation of operator deployment
on OpenShift 4.6+ (bundle format) via Openshift Marketplace.

## How to use

Example of usage below:

### Openshift

First it's necessary to create OpenshiftService:
```
OpenShiftConfiguration openShiftConfiguration = OpenShiftConfiguration.builder()
    .namespace("namespace")
    //settings for mirroring (if necessary)
      .dockerUsername(getBundleRepositoryUsername())
      .dockerPassword(getBundleRepositoryPassword())
      .dockerRegistry(getBundleRepository())
      .icspFile(getIcspFile())
    .build();
    
OpenShiftUser adminUser = new OpenShiftUser(
    adminUsername(),
    adminPassword(),
    openShiftUrl()
);

return new OpenShiftService(
    openShiftConfiguration,
    adminUser,
    defaultUser // <- can be null
);
```


### Opm

Next you'll need to create Opm, it's an abstraction for the `opm` binary. If you have one on your PATH it will be used, otherwise `opm` will
be extracted from OpenshiftService and stored in `/tmp/marketplace-utilities-opm/opm`.

```
Opm opm = new Opm(ocpSvc);
```

### Quay

Next you need to create `QuayUser`, username and password is needed because the docker binary is used for pulling and pushing.

```
QuayUser = new QuayUser("username", "password");
```

### Creating index

If you have a bundle you want to create an index from you can do the following:
```
Index index = opm.createIndex("quay.io/my-marketplace/my-index-image:0.0.1", quayUser);
Bundle myBundle = index.addBundle("quay.io/my-marketplace/my-bundle-image:0.0.1");
```

### OCP stuff

Next you need to add the index to your cluster and create a subscription.

```
index.addIndexToCluster("my-catalog");

myBundle.createSubscription();
```

This creates your operator pod and the rest is up to you.

## Other features

### Related images checks

You can check related images of your bundles with  

```
Map<String, String> imgMap = new HashMap<>();
imgMap.put("my-operator-image", "quay.io/my-operator/my-operator-image:0.0.1");
imgMap.put("my-db-image", "quay.io/my-operator/my-db-image:0.0.1");

bundle.assertSameImages(imgMap);
```

This checks the SHAs match the image IDs of your floating tags. 

### CSV checks

You can access the CSVs and CRDs via `Bundle#getCsv` and `Bundle#getCrds`
To validate any content that is displayed in the UI.

### Adding index

If you already have an index you can pull the index and create the subscription like this

```
index = opm.pullIndex("quay.io/my-marketplace/my-index-image:0.0.1", quayUser);
Bundle.createSubscription(ocpSvc, "my-operator", "my-operator-channel-1", "'MyCSV.0.0.1'", "my-catalog");
```

### Updating 

You can also test updates between channels
```
Bundle myBundle = index.addBundle("quay.io/my-marketplace/my-bundle-image:0.0.1");
Bundle myNewBundle = index.addBundle("quay.io/my-marketplace/my-bundle-image:0.1.0");

myBundle.createSubscription();

//wait and test the operator

//true for wait for the operator pod to be upgraded
myBundle.update(myNewBundle, true);
```




### Cleanup

After everything done it's recommended to clean Operatorsource and it's token:

```
index.removeIndexFromCluster();
```

Both services are stateful, so it's necessary to use same objects for cleanup.


