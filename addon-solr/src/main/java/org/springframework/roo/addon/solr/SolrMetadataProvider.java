package org.springframework.roo.addon.solr;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.addon.beaninfo.BeanInfoUtils;
import org.springframework.roo.addon.entity.EntityMetadata;
import org.springframework.roo.addon.entity.EntityMetadataProvider;
import org.springframework.roo.addon.plural.PluralMetadata;
import org.springframework.roo.classpath.PhysicalTypeIdentifier;
import org.springframework.roo.classpath.PhysicalTypeIdentifierNamingUtils;
import org.springframework.roo.classpath.PhysicalTypeMetadata;
import org.springframework.roo.classpath.details.ClassOrInterfaceTypeDetails;
import org.springframework.roo.classpath.details.FieldMetadata;
import org.springframework.roo.classpath.details.ItdTypeDetails;
import org.springframework.roo.classpath.details.MemberFindingUtils;
import org.springframework.roo.classpath.details.MethodMetadata;
import org.springframework.roo.classpath.itd.AbstractMemberDiscoveringItdMetadataProvider;
import org.springframework.roo.classpath.itd.ItdTypeDetailsProvidingMetadataItem;
import org.springframework.roo.classpath.scanner.MemberDetails;
import org.springframework.roo.metadata.MetadataIdentificationUtils;
import org.springframework.roo.model.JavaType;
import org.springframework.roo.project.Path;

/**
 * Provides {@link SolrMetadata}.
 * 
 * @author Stefan Schmidt
 * @since 1.1
 *
 */
@Component(immediate=true)
@Service
public final class SolrMetadataProvider extends AbstractMemberDiscoveringItdMetadataProvider {
	
	@Reference private EntityMetadataProvider entityMetadataProvider;

	protected void activate(ComponentContext context) {
		metadataDependencyRegistry.registerDependency(PhysicalTypeIdentifier.getMetadataIdentiferType(), getProvidesType());
		entityMetadataProvider.addMetadataTrigger(new JavaType(RooSolrSearchable.class.getName()));
		addMetadataTrigger(new JavaType(RooSolrSearchable.class.getName()));
	}
	
	protected void deactivate(ComponentContext context) {
		metadataDependencyRegistry.deregisterDependency(PhysicalTypeIdentifier.getMetadataIdentiferType(), getProvidesType());
		entityMetadataProvider.removeMetadataTrigger(new JavaType(RooSolrSearchable.class.getName()));
		removeMetadataTrigger(new JavaType(RooSolrSearchable.class.getName()));	
	}

	protected ItdTypeDetailsProvidingMetadataItem getMetadata(String metadataIdentificationString, JavaType aspectName, PhysicalTypeMetadata governorPhysicalTypeMetadata, String itdFilename) {
		// Acquire bean info (we need getters details, specifically)
		JavaType javaType = SolrMetadata.getJavaType(metadataIdentificationString);
		Path path = SolrMetadata.getPath(metadataIdentificationString);
		String entityMetadataKey = EntityMetadata.createIdentifier(javaType, path);

		// We need to parse the annotation, which we expect to be present
		SolrSearchAnnotationValues annotationValues = new SolrSearchAnnotationValues(governorPhysicalTypeMetadata);
		if (!annotationValues.isAnnotationFound() || annotationValues.searchMethod == null) {
			return null;
		}
		
		// We want to be notified if the getter info changes in any way 
		metadataDependencyRegistry.registerDependency(entityMetadataKey, metadataIdentificationString);
		EntityMetadata entityMetadata = (EntityMetadata) metadataService.get(entityMetadataKey);
		
		// Abort if we don't have getter information available
		if (entityMetadata == null) {
			return null;
		}
		
		String beanPlural = javaType.getSimpleTypeName() + "s";
		PluralMetadata plural = (PluralMetadata) metadataService.get(PluralMetadata.createIdentifier(javaType, path));
		if (plural != null) {
			beanPlural = plural.getPlural();
		}
		
		MemberDetails memberDetails = memberDetailsScanner.getMemberDetails(SolrMetadataProvider.class.getName(), (ClassOrInterfaceTypeDetails) governorPhysicalTypeMetadata.getMemberHoldingTypeDetails());
		Map<MethodMetadata, FieldMetadata> accessorDetails = new HashMap<MethodMetadata, FieldMetadata>();
		for (MethodMetadata methodMetadata : MemberFindingUtils.getMethods(memberDetails)) {
			if (isMethodOfInterest(methodMetadata)) {
				FieldMetadata fieldMetadata = BeanInfoUtils.getFieldForPropertyName(memberDetails, BeanInfoUtils.getPropertyNameForJavaBeanMethod(methodMetadata));
				if (fieldMetadata != null) {
					accessorDetails.put(methodMetadata, fieldMetadata);
				}
				// Track any changes to that method (eg it goes away)
				metadataDependencyRegistry.registerDependency(methodMetadata.getDeclaredByMetadataId(), metadataIdentificationString);
			}
		}
		// Otherwise go off and create the to Solr metadata
		return new SolrMetadata(metadataIdentificationString, aspectName, annotationValues, governorPhysicalTypeMetadata, entityMetadata, accessorDetails, beanPlural);
	}
	
	protected String getLocalMidToRequest(ItdTypeDetails itdTypeDetails) {
		// Determine if this ITD presents a method we're interested in (namely accessors)
		for (MethodMetadata method : itdTypeDetails.getDeclaredMethods()) {
			if (isMethodOfInterest(method)) {
				// We care about this ITD, so formally request an update so we can scan for it and process it
				
				// Determine the governor for this ITD, and the Path the ITD is stored within
				JavaType governorType = itdTypeDetails.getName();
				String providesType = MetadataIdentificationUtils.getMetadataClass(itdTypeDetails.getDeclaredByMetadataId());
				Path itdPath = PhysicalTypeIdentifierNamingUtils.getPath(providesType, itdTypeDetails.getDeclaredByMetadataId());
				
				//  Produce the local MID we're going to use to make the request
				String localMid = createLocalIdentifier(governorType, itdPath);
				
				// Request the local MID
				return localMid;
			}
		}
		
		return null;
	}
	
	private boolean isMethodOfInterest(MethodMetadata method) {
		return method.getMethodName().getSymbolName().startsWith("get") && method.getParameterTypes().size() == 0 && Modifier.isPublic(method.getModifier());
	}
	
	public String getItdUniquenessFilenameSuffix() {
		return "SolrSearch";
	}
	
	protected String getGovernorPhysicalTypeIdentifier(String metadataIdentificationString) {
		JavaType javaType = SolrMetadata.getJavaType(metadataIdentificationString);
		Path path = SolrMetadata.getPath(metadataIdentificationString);
		String physicalTypeIdentifier = PhysicalTypeIdentifier.createIdentifier(javaType, path);
		return physicalTypeIdentifier;
	}
	
	protected String createLocalIdentifier(JavaType javaType, Path path) {
		return SolrMetadata.createIdentifier(javaType, path);
	}

	public String getProvidesType() {
		return SolrMetadata.getMetadataIdentiferType();
	}
}